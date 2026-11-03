"""
The endpoints to capture: the services' OpenAPI descriptions (their /v3/api-docs), pasted in NetCanvas, which keeps
them by port (GET <graphUrl>/apidocs -> {"8085": {...}}).

  Endpoint      one method and path template, e.g. POST /orders or GET /orders/{processingId}.
  ApiEndpoints  the endpoints of every service, by its port; matches(port, method, uri) says whether a request is
                one of them. load() reads them from NetCanvas again.
"""

import json
import re
import urllib.request
from dataclasses import dataclass
from urllib.parse import urlsplit

HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace", "any"}   # any: typed in NetCanvas
                                                                                                 # without a method


@dataclass
class Endpoint:
    method: str          # "POST"
    path: str            # "/orders/{processingId}"
    pattern: object      # the path as a regex: "{processingId}" matches one segment, "**" anything ("/**": every path)

    @staticmethod
    def create(method, path):
        parts = re.split(r"(\{[^/}]+\}|/\*\*)", path)
        regex = "".join("[^/]+" if part.startswith("{") else "(/.*)?" if part == "/**" else re.escape(part)
                        for part in parts)
        return Endpoint(method.upper(), path, re.compile(regex + "/?"))

    def matches(self, method, path):
        return self.method in ("ANY", method.upper()) and self.pattern.fullmatch(path) is not None

    def __str__(self):
        return f"{self.method} {self.path}"


class ApiEndpoints:
    """load() runs in the web server's thread, matches() in the sniffer's: load builds a new dict and swaps it in."""

    def __init__(self, apiDocsUrl, serviceNameByPort):
        self.apiDocsUrl = apiDocsUrl                 # NetCanvas's GET /api/apidocs, None: no NetCanvas
        self.serviceNameByPort = serviceNameByPort   # {8085: "service1"}, shared with GraphSync; pasted ports are added
        self.endpointsByPort = {}                    # 8085 -> [Endpoint]

    def load(self):
        """Reads the pasted api-docs from NetCanvas, replacing the ones read before. Returns {"service1": "ok" or why
        not}. When NetCanvas can't be reached it keeps the ones it had."""
        if self.apiDocsUrl is None:
            return {"NetCanvas": "not used (--graphUrl none), so there's nowhere to paste the api-docs"}
        try:
            with urllib.request.urlopen(self.apiDocsUrl, timeout=3) as response:
                apiDocsByPort = json.load(response)
        except (OSError, ValueError) as error:       # not up, or not JSON
            return {"NetCanvas": f"{self.apiDocsUrl}: {error}"}
        endpointsByPort = {int(port): self.parse(apiDocs) for port, apiDocs in apiDocsByPort.items()}
        for port in endpointsByPort:
            # A port that isn't one of the services (--service) is drawn as a node named after it
            self.serviceNameByPort.setdefault(port, str(port))
        results = {name: "ok" if port in endpointsByPort else "no api-docs pasted in NetCanvas"
                   for port, name in self.serviceNameByPort.items()}
        self.endpointsByPort = endpointsByPort
        return results

    @staticmethod
    def parse(apiDocs):
        """{"paths": {"/orders/{processingId}": {"get": …, "put": …}}} -> [Endpoint GET …, Endpoint PUT …].
        A server URL with a path (e.g. "http://host/api") is put in front of every path."""
        servers = apiDocs.get("servers") or [{}]
        basePath = urlsplit(servers[0].get("url", "")).path.rstrip("/")
        return [Endpoint.create(method, basePath + path)
                for path, operations in apiDocs.get("paths", {}).items()
                for method in operations if method in HTTP_METHODS]

    def matches(self, port, method, uri):
        path = uri.split("?", 1)[0]
        return any(endpoint.matches(method, path) for endpoint in self.endpointsByPort.get(port, []))

    def toDict(self):
        """{"service1": ["POST /orders", …]}"""
        return {self.serviceNameByPort.get(port, str(port)): [str(endpoint) for endpoint in endpoints]
                for port, endpoints in self.endpointsByPort.items()}
