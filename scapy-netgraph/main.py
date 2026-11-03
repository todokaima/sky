#!/usr/bin/env python3
"""
Sniffs HTTP with scapy and shows the matching requests and their responses on http://localhost:8000.
These options set the filter the page starts with; it can be changed on the page.

    sudo ../.venv/bin/python main.py                 # every port on the Docker bridges; the client's actions capture
    sudo ../.venv/bin/python main.py --uris /orders /health --methods GET POST --dstPorts 8085 8080 --srcPorts 38270
    sudo ../.venv/bin/python main.py --dstPort 8080 --method POST --interfaces lo,docker0
    sudo ../.venv/bin/python main.py --dstPorts      # any server port
    sudo ../.venv/bin/python main.py --config config.json
    sudo ../.venv/bin/python main.py --interface br-1234abcd5678 --service 8085=service1 8086=service2   # node names

The config file holds the same settings; each one is a single value or a list, and the command line wins over it:
    {"uris": ["/orders", "/health"], "methods": "POST", "dstPorts": [8085, 8080], "interfaces": "lo", "webPort": 8000}
"""

import argparse
import json
import os
import threading
import time
from urllib.parse import urlsplit

from scapy.all import AsyncSniffer, conf

from apidocs import ApiEndpoints
from capture import ExchangeFilter, HttpCapture
from graph import GraphSync
from server import startServer

argumentParser = argparse.ArgumentParser(
    description="Each list option takes one value or several: --dstPort 8085, --dstPorts 8085 8080, "
                "--dstPorts 8085,8080, or the option repeated.")


def addListOption(singularName, help):
    """--uri and --uris are the same option. With no value given at all it is None, so main can apply the default."""
    argumentParser.add_argument(f"--{singularName}", f"--{singularName}s", dest=f"{singularName}s",
                                nargs="*", action="extend", help=help)


def splitValues(values, valueType=str):
    """["8085,8080", "9000"] -> [8085, 8080, 9000]"""
    return [valueType(value) for item in values for value in item.split(",") if value]


addListOption("uri", "URI prefixes, e.g. /orders /health (default: all)")
addListOption("method", "e.g. GET POST (default: all)")
addListOption("dstPort", "server ports to sniff (default: every TCP port, so the page lists all the HTTP it sees)")
addListOption("srcPort", "the client's ports (default: any)")
addListOption("interface", "where to sniff, e.g. lo docker0 (default: every Docker bridge, else lo)")
addListOption("service", "port=name, names for the nodes drawn in NetCanvas, e.g. 8085=service1 (default: the port)")
argumentParser.add_argument("--webPort", type=int, help="the port of the page (default 8000)")
argumentParser.add_argument("--webHost", help="the address the page listens on (default 127.0.0.1, only this machine; "
                                              "0.0.0.0: every address, e.g. for a client in a container)")
argumentParser.add_argument("--page", action="store_true",
                            help="also serve the page, the list, the filter and resend (default: headless, only the "
                                 "client's capture calls; scapy-minimal is the page to look at)")
argumentParser.add_argument("--graphUrl", help="NetCanvas's API (default http://localhost:29994/api; none: don't draw)")
argumentParser.add_argument("--config", help="a JSON file with any of these settings, e.g. config.json")
arguments = argumentParser.parse_args()

SETTING_NAMES = ["uris", "methods", "dstPorts", "srcPorts", "interfaces", "services", "webPort", "webHost", "graphUrl"]
config = {}
if arguments.config:
    try:
        with open(arguments.config) as configFile:
            config = json.load(configFile)
    except (OSError, ValueError) as error:
        argumentParser.error(f"can't read {arguments.config}: {error}")
    # "dstPort": 8085 and "dstPorts": [8085, 8080] are the same setting, as on the command line
    config = {(name + "s" if name + "s" in SETTING_NAMES else name): value for name, value in config.items()}
    unknownNames = set(config) - set(SETTING_NAMES)
    if unknownNames:
        argumentParser.error(f"unknown settings in {arguments.config}: {', '.join(sorted(unknownNames))} "
                             f"(known: {', '.join(SETTING_NAMES)})")


def getSetting(name):
    """The command line's value, else the config file's, else None. A single value becomes a list of one."""
    value = getattr(arguments, name)
    if value is None:
        value = config.get(name)
    if value is None or name in ("webPort", "webHost", "graphUrl"):
        return value
    if isinstance(value, dict):          # "services": {"8085": "service1"}
        value = [f"{key}={item}" for key, item in value.items()]
    return [str(item) for item in (value if isinstance(value, list) else [value])]


try:
    uris = splitValues(getSetting("uris") or [])
    methods = splitValues(getSetting("methods") or [])
    dstPortsSetting = getSetting("dstPorts")
    sourcePorts = splitValues(getSetting("srcPorts") or [], int)
    interfaces = splitValues(getSetting("interfaces") or [])
    webPort = int(getSetting("webPort") or 8000)
    webHost = getSetting("webHost") or "127.0.0.1"
    serviceNameByPort = {int(port): name for port, name in
                         (service.split("=", 1) for service in splitValues(getSetting("services") or []))}
    # Every port by default (the page's filter narrows the list); --dstPorts 8085 8086 sniffs just those
    destinationPorts = splitValues(dstPortsSetting, int) if dstPortsSetting is not None else []
except ValueError as error:
    argumentParser.error(f"a port must be a number, and a service port=name: {error}")
graphUrl = getSetting("graphUrl") or "http://localhost:29994/api"

exchangeFilter = ExchangeFilter(uris=uris, methods=methods, sourcePorts=sourcePorts, destinationPorts=destinationPorts)
# What a capture (POST /api/capture, sent by the client's Send) records: the endpoints in the services' /v3/api-docs,
# pasted in NetCanvas
apiEndpoints = ApiEndpoints(graphUrl.rstrip("/") + "/apidocs" if graphUrl != "none" else None, serviceNameByPort)
capture = HttpCapture(exchangeFilter, apiEndpoints)
def getBpfFilter(ports):
    """The kernel passes only the server ports' traffic (both directions). With any server port (None) it passes all
    TCP except the page's own polling and our calls to NetCanvas. The filter on the page then narrows it down further."""
    if ports is None:
        ignoredPorts = [webPort] + ([urlsplit(graphUrl).port or 80] if graphUrl != "none" else [])
        return "tcp" + "".join(f" and not port {port}" for port in ignoredPorts)
    return "tcp and (" + " or ".join(f"port {port}" for port in ports) + ")"


# With the services in Docker Compose (~/Desktop/testclient), their traffic, the calls between them and the curls
# forwarded to them, is on the compose network's bridge, never on lo. So by default: every Docker bridge. A
# "docker compose down/up" makes a new bridge, so then they're checked every few seconds and followed.
def findDockerBridges():
    if not os.path.isdir("/sys/class/net"):     # Windows: no Docker bridges to follow
        return []
    return sorted(name for name in os.listdir("/sys/class/net") if name.startswith("br-") or name == "docker0")


class Sniffer:
    """Sniffs the server ports on the interfaces, and starts again when either changes: Docker made a new bridge, or
    a capture brought a port that isn't sniffed yet (api-docs pasted in NetCanvas for it). The capture keeps its state."""

    def __init__(self, interfaces, ports, skipFailures):
        self.interfaces = interfaces
        self.ports = ports                 # the server ports, None: any
        self.skipFailures = skipFailures   # the Docker bridges come and go; interfaces you named must work
        self.lock = threading.Lock()
        self.start()

    def start(self):
        """Opens a socket per interface (so a wrong interface shows now) and sniffs them all. With no ports yet it
        waits for a capture to bring some."""
        self.sniffer, self.socketsByInterface = None, {}
        if self.ports == []:
            return
        socketsByInterface = {}
        for interface in self.interfaces:
            try:
                socketsByInterface[conf.L2listen(iface=interface, filter=getBpfFilter(self.ports))] = interface
            except PermissionError:
                raise SystemExit("Sniffing needs root: sudo ../.venv/bin/python main.py")
            except OSError as error:
                if not self.skipFailures:
                    raise SystemExit(f"Can't sniff on {interface}: {error}")
                print(f"Skipping {interface}: {error}")
        if not socketsByInterface:
            raise SystemExit(f"Can't sniff on any of {' '.join(self.interfaces)}")
        self.socketsByInterface = socketsByInterface
        self.sniffer = AsyncSniffer(opened_socket=socketsByInterface, prn=capture.onPacket, store=False)
        self.sniffer.start()

    def restart(self, interfaces=None, ports=None):
        with self.lock:
            if self.sniffer:
                self.sniffer.stop()        # waits for its thread, then the sockets can go
            for socket in self.socketsByInterface:
                socket.close()
            self.interfaces = interfaces or self.interfaces
            self.ports = ports if ports is not None else self.ports
            self.start()

    def addPorts(self, ports):
        """Sniffs these server ports too (nothing to do when it sniffs any port)."""
        if self.ports is None or set(ports) <= set(self.ports):
            return
        newPorts = sorted(set(self.ports) | set(ports))
        print(f"Now sniffing ports {', '.join(map(str, newPorts))}")
        self.restart(ports=newPorts)

    def followDockerBridges(self):
        while True:
            time.sleep(3)
            newBridges = findDockerBridges()
            if newBridges and newBridges != self.interfaces:
                print(f"Docker bridges changed, now sniffing {' '.join(newBridges)}")
                self.restart(interfaces=newBridges)


if hasattr(os, "geteuid") and os.geteuid() != 0:     # Windows: Npcap decides, see Sniffer.start
    raise SystemExit("Sniffing needs root: sudo ../.venv/bin/python main.py")
# None: any port (the default, and --dstPorts with no value). Otherwise these, plus the ports a capture brings
snifferPorts = destinationPorts or None
dockerBridges = findDockerBridges()
if not interfaces and dockerBridges:
    sniffer = Sniffer(dockerBridges, snifferPorts, skipFailures=True)
    threading.Thread(target=sniffer.followDockerBridges, daemon=True).start()
    sniffed = "every Docker bridge (followed as they change)"
else:
    sniffer = Sniffer(interfaces or [conf.loopback_name], snifferPorts, skipFailures=False)
    sniffed = " ".join(sniffer.interfaces)


def onCapture():
    """Runs when a capture starts, after the pasted api-docs were read: their ports are sniffed and shown too."""
    pastedPorts = list(apiEndpoints.endpointsByPort)
    sniffer.addPorts(pastedPorts)
    capture.addDestinationPorts(pastedPorts)
    if graphSync:
        graphSync.addSpecifiedNodes()   # every specified port is a node from the start, talking or not




def printStats():
    """Every 15 s, when it changed: how far the traffic gets (sniffed, parsed, captured), to see where it stops."""
    last = None
    while True:
        time.sleep(15)
        text = capture.describeStats()
        if text != last:
            print(text)
            last = text


threading.Thread(target=printStats, daemon=True).start()
graphSync = GraphSync(graphUrl, serviceNameByPort) if graphUrl != "none" else None
if graphSync:
    graphSync.start(capture)

description = (f"Sniffing {sniffed} ({getBpfFilter(snifferPorts)})" if snifferPorts != []
               else f"Sniffing {sniffed}, the ports pasted in NetCanvas from the first capture on")
where = f"http://localhost:{webPort}" + (f" (listening on {webHost})" if webHost != "127.0.0.1" else "")
print(f"{description}. " + (f"Open {where}" if arguments.page
                            else f"Headless: only the capture calls on {where}/api/capture, and {where}/api/status"))
if graphUrl != "none":
    print(f"Drawing the ports whose /v3/api-docs are pasted in NetCanvas ({graphUrl}), from each capture on "
          f"(POST http://localhost:{webPort}/api/capture, sent by the client's Send)")
try:
    startServer(capture, webHost, webPort, description, onCapture, page=arguments.page)
except KeyboardInterrupt:
    pass
