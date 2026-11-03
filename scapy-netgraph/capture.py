"""
Scapy packets to HTTP exchanges (a request and its response).

  TcpPacket       the parts of a scapy packet we need, with readable names.
  HttpExchange    a request and, once it arrives, its response.
  ExchangeFilter  which exchanges to show: by URIs, methods, destination ports, source ports.
  HttpCapture     feed it packets, it keeps the exchanges; getExchanges returns the ones that pass the filter, and
                  getExchangesByPorts the same grouped by "clientPort:serverPort", ready to serialize.
                  startCapture/stopCapture: while a capture runs, the exchanges to the services' endpoints (from
                  their /v3/api-docs) that started after it go to the listeners, i.e. to NetCanvas.
"""

import threading
from dataclasses import dataclass
from datetime import datetime

from scapy.all import IP, TCP, IPv6

from httpstream import HttpParser, TcpStream
import protocols

MAX_EXCHANGES = 500
# What an HTTP message starts with. A stream that starts with anything else isn't HTTP (Kafka, a database, TLS) or
# was joined in the middle of a message: its bytes are dropped instead of piling up
HTTP_STARTS = [b"GET ", b"POST ", b"PUT ", b"PATCH ", b"DELETE ", b"HEAD ", b"OPTIONS ", b"TRACE ", b"CONNECT ",
               b"HTTP/"]
# Not drawn when no endpoints are pasted: the services' own housekeeping
HOUSEKEEPING = ("/actuator", "/v3/api-docs", "/swagger", "/favicon.ico")


def looksLikeHttp(data):
    """True when data starts with an HTTP start, or with the beginning of one (b"PO": the rest is still coming)."""
    return any(data.startswith(start) or start.startswith(bytes(data)) for start in HTTP_STARTS)


@dataclass
class TcpPacket:
    sourceIp: str
    sourcePort: int
    destinationIp: str
    destinationPort: int
    sequenceNumber: int
    payload: bytes
    isSyn: bool
    isClosing: bool       # FIN or RST
    arrivalTime: float    # seconds since 1970

    @staticmethod
    def fromScapy(scapyPacket):
        ipLayer = scapyPacket[IP] if IP in scapyPacket else scapyPacket[IPv6]
        tcpLayer = scapyPacket[TCP]
        return TcpPacket(sourceIp=ipLayer.src, sourcePort=tcpLayer.sport,
                         destinationIp=ipLayer.dst, destinationPort=tcpLayer.dport,
                         sequenceNumber=tcpLayer.seq, payload=bytes(tcpLayer.payload),
                         isSyn=bool(tcpLayer.flags.S), isClosing=bool(tcpLayer.flags.F or tcpLayer.flags.R),
                         arrivalTime=float(scapyPacket.time))

    def getSource(self):
        return f"{self.sourceIp}:{self.sourcePort}"

    def getDestination(self):
        return f"{self.destinationIp}:{self.destinationPort}"

    def getDirectionKey(self):
        """Different for each direction: client -> server and server -> client each get their own TcpStream."""
        return (self.sourceIp, self.sourcePort, self.destinationIp, self.destinationPort)

    def getConnectionKey(self):
        """The same for both directions, so a response finds the request it answers."""
        return frozenset({(self.sourceIp, self.sourcePort), (self.destinationIp, self.destinationPort)})


@dataclass
class HttpExchange:
    id: int
    client: str                 # "127.0.0.1:38270", the side that sent the request
    server: str                 # "127.0.0.1:8085"
    sourcePort: int             # the client's port
    destinationPort: int        # the server's port
    request: object             # HttpMessage
    requestTime: float
    response: object = None     # HttpMessage, once it arrives
    responseTime: float = None

    def getDurationMs(self):
        if self.response is None:
            return None
        return round((self.responseTime - self.requestTime) * 1000, 1)

    def toDict(self):
        return {"id": self.id, "client": self.client, "server": self.server,
                "time": datetime.fromtimestamp(self.requestTime).strftime("%H:%M:%S.%f")[:-3],
                "method": self.request.getMethod(), "uri": self.request.getUri(),
                "statusCode": self.response.getStatusCode() if self.response else None,
                "durationMs": self.getDurationMs(),
                "request": messageToDict(self.request),
                "response": messageToDict(self.response) if self.response else None}

    def toCleanDict(self):
        """Just the URI and the JSON going each way, for getExchangesByPorts."""
        return {"method": self.request.getMethod(), "uri": self.request.getUri(),
                "request": self.request.getBodyAsJson(),
                "statusCode": int(self.response.getStatusCode()) if self.response else None,
                "response": self.response.getBodyAsJson() if self.response else None}

    def getPortsKey(self):
        return f"{self.sourcePort}:{self.destinationPort}"


def messageToDict(message):
    return {"startLine": message.startLine, "headers": message.headers, "body": message.getBodyAsText()}


@dataclass
class ExchangeFilter:
    """An empty criterion lets everything through. An exchange must pass all of them."""
    uris: list                     # URI prefixes, e.g. ["/orders", "/health"]: any of them matches
    methods: list                  # e.g. ["GET", "POST"]
    sourcePorts: list              # the client's ports, e.g. [38270]
    destinationPorts: list         # the server's ports, e.g. [8085, 8080]

    def matches(self, exchange):
        request = exchange.request
        if self.uris and not any(request.getUri().startswith(uri) for uri in self.uris):
            return False
        if self.methods and request.getMethod().upper() not in [method.upper() for method in self.methods]:
            return False
        if self.sourcePorts and exchange.sourcePort not in self.sourcePorts:
            return False
        if self.destinationPorts and exchange.destinationPort not in self.destinationPorts:
            return False
        return True

    def toDict(self):
        return {"uris": self.uris, "methods": self.methods,
                "sourcePorts": self.sourcePorts, "destinationPorts": self.destinationPorts}

    @staticmethod
    def fromDict(values):
        """Each value is a list, a single value or a comma separated string: ["/a", "/b"], "/a", "/a, /b".
        Raises ValueError for a port that isn't a number."""
        def toList(value, valueType=str):
            items = value if isinstance(value, list) else [value]
            texts = [text.strip() for item in items if item is not None for text in str(item).split(",")]
            return [valueType(text) for text in texts if text]
        return ExchangeFilter(uris=toList(values.get("uris", [])), methods=toList(values.get("methods", [])),
                              sourcePorts=toList(values.get("sourcePorts", []), int),
                              destinationPorts=toList(values.get("destinationPorts", []), int))


class HttpCapture:
    """onPacket runs in the sniffer thread, getExchanges and setFilter in the web server's: the lock keeps them apart.
    Every exchange is kept and the filter is applied when they are read, so a new filter also applies to the past."""

    def __init__(self, exchangeFilter, apiEndpoints):
        self.exchangeFilter = exchangeFilter
        self.apiEndpoints = apiEndpoints   # ApiEndpoints: what a capture records
        self.captureStart = None           # seconds since 1970 when the running capture started, None: not capturing
        self.captureBy = ""                # what started it, e.g. "Send on HTTP 1" (the client) or "the page"
        self.lastCapture = None            # {"by", "from", "to"} of the one before, for the page
        self.stopTimer = None              # a stop asked for with a grace period
        self.listeners = []                # called with ("request" or "response", HttpExchange) as each one is seen
        self.lock = threading.Lock()
        self.streamsByDirection = {}       # directionKey -> TcpStream
        self.unansweredByConnection = {}   # connectionKey -> [HttpExchange waiting for a response], oldest first
        self.exchanges = []                # the last MAX_EXCHANGES exchanges, oldest first
        self.exchangesByPorts = {}         # "clientPort:serverPort" -> [HttpExchange], oldest first, the same ones
        self.exchangeCount = 0
        # The stores' traffic (Redis, PostgreSQL, MySQL, MongoDB, Kafka: protocols.py), drawn while a capture runs
        self.storeListeners = []           # called with ("request" | "response", store, TcpPacket, summary, id)
        self.storeIds = {}                 # connectionKey -> [ids of requests waiting for their answer]; Mongo: (key, requestId) -> id
        self.storeCount = 0
        # What has been seen, for the terminal and the page: where traffic stops, if it does
        self.stats = {"packets": 0, "withData": 0, "httpRequests": 0, "httpCaptured": 0, "storeCommands": 0, "storeCaptured": 0}

    def onPacket(self, scapyPacket):
        if TCP not in scapyPacket:
            return
        packet = TcpPacket.fromScapy(scapyPacket)
        with self.lock:
            self.stats["packets"] += 1
            if packet.payload:
                self.stats["withData"] += 1
            store = protocols.storeOf(packet.destinationPort) or protocols.storeOf(packet.sourcePort)
            if store:
                if packet.payload:
                    self.onStorePacket(store, packet)
                return
            directionKey = packet.getDirectionKey()
            if directionKey not in self.streamsByDirection:
                self.streamsByDirection[directionKey] = TcpStream()
            stream = self.streamsByDirection[directionKey]

            stream.addSegment(packet.sequenceNumber, packet.payload, packet.isSyn)
            try:
                message = HttpParser.takeNextMessage(stream)
                while message is not None:
                    self.handleMessage(message, packet)
                    message = HttpParser.takeNextMessage(stream)
            except (ValueError, IndexError):     # a bad Content-Length or chunk size: not something we can read
                stream.orderedBytes.clear()
            if stream.orderedBytes and not looksLikeHttp(stream.orderedBytes[:8]):
                stream.orderedBytes.clear()

            if packet.isClosing:
                del self.streamsByDirection[directionKey]

    def onStorePacket(self, store, packet):
        """A store's packet: what was asked or answered (protocols.py), to the listeners while a capture runs."""
        toServer = protocols.storeOf(packet.destinationPort) == store
        connectionKey = packet.getConnectionKey()
        for event, summary, correlation in protocols.read(store, packet.payload, toServer):
            if event == "request":
                self.stats["storeCommands"] += 1
                self.storeCount += 1
                storeId = f"db{self.storeCount}"
                if correlation is not None:
                    self.storeIds[(connectionKey, correlation)] = storeId
                else:
                    self.storeIds.setdefault(connectionKey, []).append(storeId)
                    del self.storeIds[connectionKey][:-50]      # answers that never came don't pile up
            else:
                waiting = self.storeIds.get(connectionKey)
                storeId = (self.storeIds.pop((connectionKey, correlation), None) if correlation is not None
                           else waiting.pop(0) if waiting else None)
                if storeId is None:
                    continue                                     # the answer to something asked before we looked
            if self.captureStart is not None and packet.arrivalTime >= self.captureStart:
                self.stats["storeCaptured"] += event == "request"
                for listener in self.storeListeners:
                    listener(event, store, packet, summary, storeId)

    def handleMessage(self, message, packet):
        connectionKey = packet.getConnectionKey()
        if message.isRequest():
            self.exchangeCount += 1
            exchange = HttpExchange(id=self.exchangeCount, client=packet.getSource(), server=packet.getDestination(),
                                    sourcePort=packet.sourcePort, destinationPort=packet.destinationPort,
                                    request=message, requestTime=packet.arrivalTime)
            self.unansweredByConnection.setdefault(connectionKey, []).append(exchange)
            self.exchanges.append(exchange)
            self.stats["httpRequests"] += 1
            # The same object: when its response arrives it shows up here too
            self.exchangesByPorts.setdefault(exchange.getPortsKey(), []).append(exchange)
            # With constant traffic, keep only the last MAX_EXCHANGES, in both
            for oldExchange in self.exchanges[:-MAX_EXCHANGES]:
                sameKey = self.exchangesByPorts[oldExchange.getPortsKey()]
                sameKey.remove(oldExchange)
                if not sameKey:
                    del self.exchangesByPorts[oldExchange.getPortsKey()]
            del self.exchanges[:-MAX_EXCHANGES]
            self.tellListeners("request", exchange)
        else:
            unanswered = self.unansweredByConnection.get(connectionKey)
            if not unanswered:
                return                       # we never saw the request it answers
            exchange = unanswered.pop(0)     # HTTP/1.1 answers requests in the order they were sent
            if not unanswered:
                del self.unansweredByConnection[connectionKey]   # one per connection otherwise: they'd pile up
            exchange.response = message
            exchange.responseTime = packet.arrivalTime
            self.tellListeners("response", exchange)

    def tellListeners(self, event, exchange):
        """Only for the captured exchanges that pass the filter. Runs under the lock, so a listener must be quick."""
        if self.isCaptured(exchange) and self.exchangeFilter.matches(exchange):
            self.stats["httpCaptured"] += event == "request"
            for listener in self.listeners:
                listener(event, exchange)

    def isCaptured(self, exchange):
        """Sent while a capture runs (the response of an older request isn't), to one of the endpoints pasted in
        NetCanvas; with none pasted, any request but the services' housekeeping (/actuator, /v3/api-docs)."""
        if self.captureStart is None or exchange.requestTime < self.captureStart:
            return False
        if not self.apiEndpoints.endpointsByPort:
            return not exchange.request.getUri().startswith(HOUSEKEEPING)
        return self.apiEndpoints.matches(exchange.destinationPort, exchange.request.getMethod(), exchange.request.getUri())

    def getExchanges(self):
        """The ones that pass the filter, newest first, as dicts ready for JSON."""
        with self.lock:
            return [{**exchange.toDict(), "captured": self.isCaptured(exchange)}
                    for exchange in reversed(self.exchanges) if self.exchangeFilter.matches(exchange)]

    def getExchangesByPorts(self):
        """{"38270:8085": [{"method", "uri", "request", "statusCode", "response"}, ...]} for the exchanges that pass
        the filter, oldest first in each list. Plain dicts and lists, so json.dumps can serialize it."""
        with self.lock:
            exchangesByPorts = {}
            for portsKey, exchanges in self.exchangesByPorts.items():
                matching = [exchange.toCleanDict() for exchange in exchanges if self.exchangeFilter.matches(exchange)]
                if matching:
                    exchangesByPorts[portsKey] = matching
            return exchangesByPorts

    def getFilter(self):
        with self.lock:
            return self.exchangeFilter.toDict()

    def setFilter(self, exchangeFilter):
        with self.lock:
            self.exchangeFilter = exchangeFilter

    def clear(self):
        """Forgets the exchanges shown so far. Requests still waiting for a response are kept, so pairing stays right."""
        with self.lock:
            self.exchanges = []
            self.exchangesByPorts = {}

    def startCapture(self, startTime, by="the page"):
        """Records from startTime on (the endpoints are loaded already), and starts the list afresh."""
        self.clear()
        with self.lock:
            if self.stopTimer:
                self.stopTimer.cancel()     # a new capture before the last one's grace period ended
                self.stopTimer = None
            self.captureStart = startTime
            self.captureBy = by

    def addDestinationPorts(self, ports):
        """Lets these server ports through the filter too, unless it lets any port through."""
        with self.lock:
            if self.exchangeFilter.destinationPorts:
                self.exchangeFilter.destinationPorts = sorted(set(self.exchangeFilter.destinationPorts) | set(ports))

    def stopCapture(self, graceSeconds=0):
        """Stops drawing, now or after graceSeconds (so the last responses still come in)."""
        if graceSeconds > 0:
            with self.lock:
                if self.stopTimer:
                    self.stopTimer.cancel()
                started = self.captureStart     # only that capture: a new one started meanwhile keeps running
                self.stopTimer = threading.Timer(graceSeconds, lambda: self.captureStart == started and self.stopCapture())
                self.stopTimer.daemon = True
                self.stopTimer.start()
            return
        with self.lock:
            self.stopTimer = None
            if self.captureStart is not None:
                clock = lambda seconds: datetime.fromtimestamp(seconds).strftime("%H:%M:%S")
                self.lastCapture = {"by": self.captureBy, "from": clock(self.captureStart), "to": clock(datetime.now().timestamp())}
            self.captureStart = None

    def getCapture(self):
        with self.lock:
            since = datetime.fromtimestamp(self.captureStart).strftime("%H:%M:%S") if self.captureStart else None
            return {"capturing": self.captureStart is not None, "since": since, "by": self.captureBy,
                    "last": self.lastCapture, "endpoints": self.apiEndpoints.toDict(), "stats": dict(self.stats)}

    def describeStats(self):
        with self.lock:
            s = dict(self.stats)
        return (f"seen {s['packets']} packets ({s['withData']} with data) · HTTP {s['httpRequests']} requests, "
                f"{s['httpCaptured']} captured · stores {s['storeCommands']} commands, {s['storeCaptured']} captured")
