#!/usr/bin/env python3
"""
Sniffs HTTP with scapy and shows the requests and their responses on http://localhost:29922/scapyminimal. Standalone: no Docker,
no NetCanvas.

    sudo .venv/bin/python main.py                          # every port on lo
    sudo .venv/bin/python main.py --dstPorts 8085 8086 --uris /orders
    sudo .venv/bin/python main.py --interfaces lo eth0 --methods POST
    sudo .venv/bin/python main.py --config config.json

The options only set what the page starts with: the URIs, methods, server ports, client ports and interfaces can
all be changed on the page. The server ports are what is sniffed (none: every port); the rest only filters the list.

The config file holds the same settings; each one is a single value or a list, and the command line wins over it:
    {"uris": ["/orders"], "methods": "POST", "dstPorts": [8085, 8086], "interfaces": "lo", "webPort": 29922}
"""

import argparse
import json
import os
import threading

from scapy.all import AsyncSniffer, conf

from capture import ExchangeFilter, HttpCapture
from server import startServer

argumentParser = argparse.ArgumentParser(
    description="Each list option takes one value or several: --dstPort 8085, --dstPorts 8085 8086, "
                "--dstPorts 8085,8086, or the option repeated.")


def addListOption(singularName, help):
    """--uri and --uris are the same option."""
    argumentParser.add_argument(f"--{singularName}", f"--{singularName}s", dest=f"{singularName}s",
                                nargs="*", action="extend", help=help)


def splitValues(values, valueType=str):
    """["8085,8086", "9000"] -> [8085, 8086, 9000]"""
    return [valueType(value) for item in values for value in item.split(",") if value]


addListOption("uri", "URI prefixes, e.g. /orders /health (default: all)")
addListOption("method", "e.g. GET POST (default: all)")
addListOption("dstPort", "the server ports to sniff, e.g. 8085 8086 (default: every port)")
addListOption("srcPort", "the client's ports (default: any)")
addListOption("interface", "where to sniff, e.g. lo eth0 (default: lo)")
argumentParser.add_argument("--webPort", type=int, help="the port of the page (default 29922)")
argumentParser.add_argument("--webHost", help="the address the page listens on (default 127.0.0.1, only this machine; "
                                              "0.0.0.0: every address)")
argumentParser.add_argument("--config", help="a JSON file with any of these settings, e.g. config.json")
arguments = argumentParser.parse_args()

SETTING_NAMES = ["uris", "methods", "dstPorts", "srcPorts", "interfaces", "webPort", "webHost"]
config = {}
if arguments.config:
    try:
        with open(arguments.config) as configFile:
            config = json.load(configFile)
    except (OSError, ValueError) as error:
        argumentParser.error(f"can't read {arguments.config}: {error}")
    # "dstPort": 8085 and "dstPorts": [8085, 8086] are the same setting, as on the command line
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
    if value is None or name in ("webPort", "webHost"):
        return value
    return [str(item) for item in (value if isinstance(value, list) else [value])]


try:
    uris = splitValues(getSetting("uris") or [])
    methods = splitValues(getSetting("methods") or [])
    destinationPorts = splitValues(getSetting("dstPorts") or [], int)
    sourcePorts = splitValues(getSetting("srcPorts") or [], int)
    interfaces = splitValues(getSetting("interfaces") or [])
    webPort = int(getSetting("webPort") or 29922)
    webHost = getSetting("webHost") or "127.0.0.1"
except ValueError as error:
    argumentParser.error(f"a port must be a number: {error}")

capture = HttpCapture(ExchangeFilter(uris=uris, methods=methods, sourcePorts=sourcePorts,
                                     destinationPorts=destinationPorts))


def getBpfFilter(ports):
    """The kernel passes only the server ports' traffic (both directions). With no ports: all TCP except the page's
    own polling. The filter on the page then narrows it down further."""
    if not ports:
        return f"tcp and not port {webPort}"
    return "tcp and (" + " or ".join(f"port {port}" for port in ports) + ")"


class Sniffer:
    """Sniffs the server ports on the interfaces, and starts again when the page changes either. The capture keeps
    its state, so what was captured stays."""

    def __init__(self, interfaces, ports):
        self.defaultInterfaces = interfaces or [conf.loopback_name]   # --interfaces, else lo
        self.pageInterfaces = []                                      # typed on the page, [] : the default
        self.wantedPorts = list(ports)                                # [] : every port
        self.interfaces, self.ports = None, None                      # what is sniffed now
        self.sniffer, self.socketsByInterface = None, {}
        self.lock = threading.Lock()

    def restart(self):
        """Sniffs what's wanted now, if that changed. Opens a socket per interface, so a wrong one shows now.
        Returns the problems, e.g. ["eth9: No such device"]; the interfaces that work are sniffed anyway."""
        with self.lock:
            interfaces, ports = self.pageInterfaces or self.defaultInterfaces, sorted(set(self.wantedPorts))
            if (interfaces, ports) == (self.interfaces, self.ports):
                return []
            if self.sniffer:
                self.sniffer.stop()        # waits for its thread, then the sockets can go
            for socket in self.socketsByInterface:
                socket.close()
            self.sniffer, self.socketsByInterface = None, {}
            self.interfaces, self.ports = interfaces, ports
            errors = []
            for interface in interfaces:
                try:
                    self.socketsByInterface[conf.L2listen(iface=interface, filter=getBpfFilter(ports))] = interface
                except OSError as error:
                    errors.append(f"{interface}: {error}")
            if self.socketsByInterface:
                self.sniffer = AsyncSniffer(opened_socket=self.socketsByInterface, prn=capture.onPacket, store=False)
                self.sniffer.start()
        print(self.describe())
        for error in errors:
            print(f"Can't sniff on {error}")
        return errors

    def setFromPage(self, ports, interfaces):
        self.wantedPorts, self.pageInterfaces = list(ports), list(interfaces)
        return self.restart()

    def describe(self):
        """What is sniffed, for the page and the terminal."""
        if not self.socketsByInterface:
            return f"Not sniffing: none of {' '.join(self.interfaces or [])} can be opened"
        sniffing = "port " + ", ".join(map(str, self.ports)) if self.ports else "every port"
        return f"Sniffing {sniffing} on {' '.join(self.socketsByInterface.values())}"


if hasattr(os, "geteuid") and os.geteuid() != 0:     # Windows: Npcap decides
    raise SystemExit("Sniffing needs root: sudo .venv/bin/python main.py")
sniffer = Sniffer(interfaces, destinationPorts)
startupErrors = sniffer.restart()
if startupErrors:
    raise SystemExit("Can't sniff on " + "; ".join(startupErrors))

print(f"Open http://localhost:{webPort}/scapyminimal" + (f" (listening on {webHost})" if webHost != "127.0.0.1" else ""))
try:
    startServer(capture, webHost, webPort, sniffer)
except KeyboardInterrupt:
    pass
