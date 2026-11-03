"""
Scapy packets to HTTP exchanges (a request and its response).

  TcpPacket       the parts of a scapy packet we need, with readable names.
  HttpExchange    a request and, once it arrives, its response.
  ExchangeFilter  which exchanges to show: by URIs, methods, destination ports, source ports.
  HttpCapture     feed it packets, it keeps the exchanges; getExchanges returns the ones that pass the filter, and
                  getExchangesByPorts the same grouped by "clientPort:serverPort", ready to serialize.
"""

import threading
from dataclasses import dataclass
from datetime import datetime

from scapy.all import IP, TCP, IPv6

from httpstream import HttpParser, TcpStream
import protocols

# How the stores' rows are named in the list: their "method"
STORE_METHODS = {"redis": "REDIS", "mongodb": "MONGO", "postgres": "PGSQL", "mysql": "MYSQL"}
STORE_NAMES = {"redis": "Redis", "mongodb": "MongoDB", "postgres": "PostgreSQL", "mysql": "MySQL / MariaDB"}


class StoreMessage:
    """A store's command or reply, shaped like an HttpMessage so the list shows it like a request and its response:
    "REDIS GET stock:Tea" -> "REDIS OK" with the reply as its body."""

    def __init__(self, store, request, text, status=None):
        self.store, self.request, self.text, self.status = store, request, text, status
        method = STORE_METHODS[store]
        self.startLine = f"{method} {text}" if request else f"{method} {status}"
        self.headers = [("Store", STORE_NAMES[store])]
        self.body = b"" if request else text.encode()

    def isRequest(self):
        return self.request

    def getMethod(self):
        return STORE_METHODS[self.store]

    def getUri(self):
        return self.text

    def getStatusCode(self):
        return self.status

    def getHeader(self, name, defaultValue=""):
        return dict(self.headers).get(name, defaultValue)

    def getBodyAsJson(self):
        return None if self.request else self.text

    def getBodyAsText(self):
        return "" if self.request else self.text

MAX_EXCHANGES = 500
# What an HTTP message starts with. A stream that starts with anything else isn't HTTP (Kafka, a database, TLS) or
# was joined in the middle of a message: its bytes are dropped instead of piling up
HTTP_STARTS = [b"GET ", b"POST ", b"PUT ", b"PATCH ", b"DELETE ", b"HEAD ", b"OPTIONS ", b"TRACE ", b"CONNECT ",
               b"HTTP/"]


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

    def __init__(self, exchangeFilter):
        self.exchangeFilter = exchangeFilter
        self.lock = threading.Lock()
        self.streamsByDirection = {}       # directionKey -> TcpStream
        self.unansweredByConnection = {}   # connectionKey -> [HttpExchange waiting for a response], oldest first
        self.exchanges = []                # the last MAX_EXCHANGES exchanges, oldest first
        self.exchangesByPorts = {}         # "clientPort:serverPort" -> [HttpExchange], oldest first, the same ones
        self.exchangeCount = 0
        # A store's commands waiting for their reply: connectionKey -> [HttpExchange], MongoDB: (key, requestId) -> one
        self.storeWaiting = {}

    def onPacket(self, scapyPacket):
        if TCP not in scapyPacket:
            return
        packet = TcpPacket.fromScapy(scapyPacket)
        with self.lock:
            # The stores' own protocols: their commands and replies are listed too (Kafka's aren't: its replies
            # aren't read, so they'd all be waiting)
            store = protocols.storeOf(packet.destinationPort) or protocols.storeOf(packet.sourcePort)
            if store:
                if store != "kafka" and packet.payload:
                    self.handleStorePacket(store, packet)
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

    def handleStorePacket(self, store, packet):
        """A store's packet: each command becomes a row, each reply its response (in order on the connection, or by
        request id for MongoDB)."""
        toServer = protocols.storeOf(packet.destinationPort) == store
        connectionKey = packet.getConnectionKey()
        waiting = self.storeWaiting
        for event, summary, correlation in protocols.read(store, packet.payload, toServer):
            if event == "request":
                path = summary["path"] if summary["method"] in ("SQL", "REDIS", "MONGO") else f"{summary['method']} {summary['path']}"
                exchange = self.addExchange(StoreMessage(store, True, path), packet)
                if correlation is not None:
                    waiting[(connectionKey, correlation)] = exchange
                else:
                    waiting.setdefault(connectionKey, []).append(exchange)
                    del waiting[connectionKey][:-50]
            else:
                if correlation is not None:
                    exchange = waiting.pop((connectionKey, correlation), None)
                else:
                    queue = waiting.get(connectionKey) or []
                    exchange = queue.pop(0) if queue else None
                if exchange is None:
                    continue                          # the reply to something asked before we looked
                exchange.response = StoreMessage(store, False, summary.get("reply", ""), summary.get("status", "OK"))
                exchange.responseTime = packet.arrivalTime

    def addExchange(self, message, packet):
        """A new row for this request, keeping only the last MAX_EXCHANGES."""
        self.exchangeCount += 1
        exchange = HttpExchange(id=self.exchangeCount, client=packet.getSource(), server=packet.getDestination(),
                                sourcePort=packet.sourcePort, destinationPort=packet.destinationPort,
                                request=message, requestTime=packet.arrivalTime)
        self.exchanges.append(exchange)
        self.exchangesByPorts.setdefault(exchange.getPortsKey(), []).append(exchange)
        for oldExchange in self.exchanges[:-MAX_EXCHANGES]:
            sameKey = self.exchangesByPorts[oldExchange.getPortsKey()]
            sameKey.remove(oldExchange)
            if not sameKey:
                del self.exchangesByPorts[oldExchange.getPortsKey()]
        del self.exchanges[:-MAX_EXCHANGES]
        return exchange

    def handleMessage(self, message, packet):
        connectionKey = packet.getConnectionKey()
        if message.isRequest():
            exchange = self.addExchange(message, packet)
            self.unansweredByConnection.setdefault(connectionKey, []).append(exchange)
            return
        unanswered = self.unansweredByConnection.get(connectionKey)
        if not unanswered:
            return                       # we never saw the request it answers
        exchange = unanswered.pop(0)     # HTTP/1.1 answers requests in the order they were sent
        if not unanswered:
            del self.unansweredByConnection[connectionKey]   # one per connection otherwise: they'd pile up
        exchange.response = message
        exchange.responseTime = packet.arrivalTime

    def getExchanges(self):
        """The ones that pass the filter, newest first, as dicts ready for JSON."""
        with self.lock:
            return [exchange.toDict() for exchange in reversed(self.exchanges) if self.exchangeFilter.matches(exchange)]

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
