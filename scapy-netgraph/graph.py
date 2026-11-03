"""
Posts every captured request and response to NetCanvas (../netgraph, its API on :29994/api) as it happens:
those of a capture, i.e. to the services' endpoints (their /v3/api-docs, pasted in NetCanvas) since POST /api/capture.

  a node per port: each specified one (pasted in NetCanvas or named with --service) as soon as a capture starts,
  and the destination and source port of every captured request, so the graph is complete,
  an edge per pair that talked, created the first time they do,
  each request, then its response, a message on that edge; NetCanvas pairs them by "id" and keeps the history.

Who called: a service's IP is learned from the requests it receives (172.18.0.5:8085 → its node 8085), so a request
from 172.18.0.5 was sent by that service. Any other caller (curl, the client) is drawn as its own source port. On lo
everything is 127.0.0.1, so there every caller is its source port: sniff the Docker network's bridge (br-...) to
see the services call each other.
"""

import json
import queue

from containers import Containers
import threading
import urllib.error
import urllib.request

def isLoopback(ip):
    return ip.startswith("127.") or ip == "::1"


def splitAddress(address):
    """"172.18.0.5:8085" -> "172.18.0.5" (rsplit, so IPv6 addresses keep their colons)."""
    return address.rsplit(":", 1)[0]


class GraphSync:
    """onExchange runs in the sniffer thread and only queues; one worker thread does the HTTP calls, in order."""

    def __init__(self, apiUrl, serviceNameByPort):
        self.apiUrl = apiUrl.rstrip("/")
        self.serviceNameByPort = serviceNameByPort   # {8085: "service1", 8086: "service2"}
        self.serviceNameByIp = {}                    # "172.18.0.5" -> "service1", learned from the traffic
        self.portByName = {}                         # "8085" / "service1" -> 8085, for the nodes' metadata
        self.nodeSpecs = {}                          # the stores' nodes and their callers: label -> {"type", "metadata"}
        self.containers = Containers()               # container names for the IPs, looked up in the worker thread
        self.messages = queue.Queue()
        self.lastError = None

    def start(self, capture):
        capture.listeners.append(self.onExchange)
        capture.storeListeners.append(self.onStore)
        threading.Thread(target=self.postForever, daemon=True).start()

    def onExchange(self, event, exchange):
        serverName = self.nameOf(exchange.destinationPort)
        serverIp = splitAddress(exchange.server)
        if not isLoopback(serverIp):
            self.serviceNameByIp[serverIp] = serverName
        # A service calling: its node. Anyone else: a node for the port it called from
        callerName = self.serviceNameByIp.get(splitAddress(exchange.client)) or self.nameOf(exchange.sourcePort)
        if event == "request":
            self.messages.put({"from": callerName, "to": serverName, "id": exchange.id, "request": {
                "method": exchange.request.getMethod(), "path": exchange.request.getUri(),
                "body": exchange.request.getBodyAsJson()}})
        else:
            self.messages.put({"from": serverName, "to": callerName, "id": exchange.id, "response": {
                "status": int(exchange.response.getStatusCode()), "body": exchange.response.getBodyAsJson()}})

    def onStore(self, event, store, packet, summary, storeId):
        """A store's command or answer: queued with the addresses, named in the worker (docker may be slow)."""
        toServer = event == "request"
        self.messages.put({"store": store, "event": event, "summary": summary, "id": storeId,
                           "serverIp": packet.destinationIp if toServer else packet.sourceIp,
                           "serverPort": packet.destinationPort if toServer else packet.sourcePort,
                           "clientIp": packet.sourceIp if toServer else packet.destinationIp})

    def storeMessage(self, item):
        """The store's node ("service1-db:5432"), its caller's (a service's node, a container, "host"), the message."""
        serverName = f"{self.containers.nameOf(item['serverIp']) or item['store']}:{item['serverPort']}"
        callerName = (self.serviceNameByIp.get(item["clientIp"]) or self.containers.nameOf(item["clientIp"])
                      or item["clientIp"])
        self.nodeSpecs.setdefault(serverName, {"type": "Data", "metadata": {
            "port": item["serverPort"], "protocol": item["store"], "ip": item["serverIp"]}})
        if callerName not in self.serviceNameByIp.values():
            self.nodeSpecs.setdefault(callerName, {"type": "External", "metadata": {"ip": item["clientIp"]}})
        summary = item["summary"]
        if item["store"] == "kafka":         # no answers read for Kafka: one plain message per request
            return {"from": callerName, "to": serverName, "data": summary}
        if item["event"] == "request":
            return {"from": callerName, "to": serverName, "id": item["id"], "request": summary}
        return {"from": serverName, "to": callerName, "id": item["id"], "response": summary}

    def nameOf(self, port):
        """The node of a port: its --service name, else the port itself ("8085")."""
        self.portByName.setdefault(self.serviceNameByPort.get(port, str(port)), port)
        return self.serviceNameByPort.get(port, str(port))

    def addSpecifiedNodes(self):
        """When a capture starts: a node for every specified port, even before it talks."""
        self.messages.put({"nodes": [self.nameOf(port) for port in sorted(self.serviceNameByPort)]})

    def postForever(self):
        while True:
            message = self.messages.get()
            try:
                self.post(message)
                if self.lastError:
                    print("NetCanvas: reachable again")
                self.lastError = None
            except (OSError, ValueError) as error:   # urllib errors are OSErrors; the message is dropped
                if str(error) != self.lastError:     # say it once, not for every message
                    print(f"NetCanvas ({self.apiUrl}): {error}")
                self.lastError = str(error)

    def post(self, message):
        """On a pair that is already linked this is one call. The first time a pair talks (or after NetCanvas
        restarted) the link is missing: create its nodes and the edge, then send the message again."""
        if "nodes" in message:
            self.ensureNodes(message["nodes"])
            return
        if "store" in message:
            message = self.storeMessage(message)
        try:
            self.call("POST", "/messages", message)
            return
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
        nodeIdByLabel = self.ensureNodes([message["from"], message["to"]])
        try:
            self.call("POST", "/edges", {"from": nodeIdByLabel[message["from"]], "to": nodeIdByLabel[message["to"]]})
        except urllib.error.HTTPError as error:
            if error.code != 409:                    # 409: linked already, e.g. the nodes were there and the edge too
                raise
        self.call("POST", "/messages", message)

    def ensureNodes(self, names):
        """Creates the nodes that aren't there yet; returns every node's id by label. A specified port is a
        Service, a port seen only as a caller (curl, the client) is External."""
        nodeIdByLabel = {node["label"]: node["id"] for node in self.call("GET", "/graph")["nodes"]}
        specified = set(self.serviceNameByPort.values())
        learned = set(self.serviceNameByIp.values())
        for name in names:
            if name not in nodeIdByLabel:
                kind = "Service" if name in specified or name in learned else "External"
                node = {"label": name, "type": kind, "metadata": {"port": self.portByName.get(name)}}
                if name in self.nodeSpecs:
                    node = {"label": name, **self.nodeSpecs[name]}
                nodeIdByLabel[name] = self.call("POST", "/nodes", node)["id"]
        return nodeIdByLabel

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.apiUrl + path, data=data, method=method,
                                         headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=5) as response:
            text = response.read()
        return json.loads(text) if text else None
