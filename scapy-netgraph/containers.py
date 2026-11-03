"""
Names for the IPs seen on the Docker bridges: which container has an address ("service1-db"), and which addresses are
the bridges' own gateways (this machine: a published port reached from here, e.g. the client's psql or mongosh, comes
from there). Read with the docker CLI (main runs as root, so it may), at most every 15 s; without docker: no names.
"""

import json
import subprocess
import threading
import time

REFRESH_SECONDS = 15


class Containers:
    def __init__(self):
        self.nameByIp = {}
        self.gateways = set()
        self.readAt = 0
        self.lock = threading.Lock()
        self.available = True

    def refresh(self):
        if not self.available or time.time() - self.readAt < REFRESH_SECONDS:
            return
        self.readAt = time.time()
        try:
            ids = subprocess.run(["docker", "ps", "-q"], capture_output=True, text=True, timeout=5).stdout.split()
            networks = subprocess.run(["docker", "network", "ls", "-q"], capture_output=True, text=True, timeout=5).stdout.split()
            nameByIp, gateways = {}, set()
            if ids:
                for container in json.loads(subprocess.run(["docker", "inspect", *ids], capture_output=True, text=True,
                                                           timeout=10).stdout or "[]"):
                    for network in (container.get("NetworkSettings", {}).get("Networks") or {}).values():
                        if network.get("IPAddress"):
                            nameByIp[network["IPAddress"]] = container.get("Name", "").lstrip("/")
            if networks:
                for network in json.loads(subprocess.run(["docker", "network", "inspect", *networks], capture_output=True,
                                                         text=True, timeout=10).stdout or "[]"):
                    for config in (network.get("IPAM", {}).get("Config") or []):
                        if config.get("Gateway"):
                            gateways.add(config["Gateway"])
            with self.lock:
                self.nameByIp, self.gateways = nameByIp, gateways
        except FileNotFoundError:
            self.available = False          # no docker CLI: no names, and no point asking again
        except (subprocess.SubprocessError, ValueError, OSError):
            pass                            # try again at the next refresh

    def nameOf(self, ip):
        """The container's name, "host" for this machine (a gateway or loopback), else None."""
        self.refresh()
        with self.lock:
            if ip in self.nameByIp:
                return self.nameByIp[ip]
            if ip in self.gateways or ip.startswith("127.") or ip == "::1":
                return "host"
        return None
