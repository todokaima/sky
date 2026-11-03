"""
Resends a captured request, as edited on the page: POST /api/resend with
    {"target": "127.0.0.1:8085", "method": "POST", "uri": "/orders", "headers": [["Host", "..."], ...], "body": "..."}
It goes out over a normal socket (the captured connection is long gone), with Content-Length worked out again and
Connection: close, and the sniffer captures it like any other request. Answers
    {"ok": true, "statusLine": "HTTP/1.1 201 ", "statusCode": 201, "headers": [[name, value], ...], "body": "...",
     "durationMs": 12.3}   or   {"ok": false, "error": "why"}.
"""

import json
import socket
import time

from httpstream import HttpParser, TcpStream

TIMEOUT_SECONDS = 15
# Worked out again for the edited body, or would no longer be true of it
DROPPED_HEADERS = {"content-length", "transfer-encoding", "connection"}


def splitTarget(target):
    """"127.0.0.1:8085" / "[::1]:8085" / "localhost:8085" -> (host, port)."""
    host, _, port = str(target).strip().rpartition(":")
    if not host or not port.isdigit():
        raise ValueError(f"the target must look like host:port, got {target!r}")
    return host.strip("[]"), int(port)


def resend(data):
    try:
        host, port = splitTarget(data.get("target", ""))
        method = str(data.get("method", "GET")).strip().upper() or "GET"
        uri = str(data.get("uri", "/")).strip() or "/"
        if any(ch.isspace() for ch in method + uri):
            raise ValueError("the method and the URI can't contain spaces")
        body = str(data.get("body") or "").encode()
        headers = [(str(name).strip(), str(value).strip()) for name, value in data.get("headers", [])
                   if str(name).strip() and str(name).strip().lower() not in DROPPED_HEADERS]
        if not any(name.lower() == "host" for name, _ in headers):
            headers.insert(0, ("Host", f"{host}:{port}"))
        if body or method in ("POST", "PUT", "PATCH"):
            headers.append(("Content-Length", str(len(body))))
        headers.append(("Connection", "close"))
        head = f"{method} {uri} HTTP/1.1\r\n" + "".join(f"{name}: {value}\r\n" for name, value in headers) + "\r\n"
    except (ValueError, TypeError, AttributeError) as error:
        return {"ok": False, "error": str(error)}

    started = time.time()
    try:
        with socket.create_connection((host, port), timeout=TIMEOUT_SECONDS) as connection:
            connection.sendall(head.encode("iso-8859-1") + body)
            received = bytearray()
            while True:
                chunk = connection.recv(65536)
                if not chunk:
                    break
                received += chunk
    except OSError as error:
        return {"ok": False, "error": f"{host}:{port}: {error}"}
    durationMs = round((time.time() - started) * 1000, 1)

    stream = TcpStream()
    stream.orderedBytes = bytearray(received)
    try:
        response = HttpParser.takeNextMessage(stream)
    except (ValueError, IndexError):
        response = None
    if response is None:
        # Not a complete HTTP message (or a body that ends with the connection): show what came back
        text = bytes(received).decode("utf-8", "replace")
        headEnd = text.find("\r\n\r\n")
        return {"ok": True, "statusLine": text.split("\r\n", 1)[0], "statusCode": None, "headers": [],
                "body": text[headEnd + 4:] if headEnd >= 0 else text, "durationMs": durationMs}
    statusCode = response.getStatusCode()
    return {"ok": True, "statusLine": response.startLine, "statusCode": int(statusCode) if statusCode.isdigit() else None,
            "headers": response.headers, "body": response.getBodyAsText(), "durationMs": durationMs}


if __name__ == "__main__":
    print(json.dumps(resend({"target": "127.0.0.1:8085", "method": "GET", "uri": "/products"}), indent=2))
