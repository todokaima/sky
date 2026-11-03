"""
Bytes to HTTP, with no scapy:
  TcpStream   puts the segments of one direction of a TCP connection back in order.
  HttpParser  takes complete HTTP messages off the front of a TcpStream.
  HttpMessage one request or response.

Left out on purpose (httpstream.py handles them): sequence numbers wrapping around 2^32, partly overlapping
segments, joining a connection in the middle of a message, and bodies that end when the connection closes.
"""

import json
import re
from dataclasses import dataclass

HEADER_END = b"\r\n\r\n"
# What an HTTP/1.x start line looks like: "POST /orders HTTP/1.1", "HTTP/1.1 201 Created" (the reason may be missing)
REQUEST_LINE = re.compile(r"[A-Z]+ \S+ HTTP/\d\.\d")
STATUS_LINE = re.compile(r"HTTP/\d\.\d \d{3}( .*)?")
LINE_END = b"\r\n"


class TcpStream:
    """One direction of a TCP connection. TCP numbers every byte; we use that number to put the bytes in order."""

    def __init__(self):
        self.nextSequenceNumber = None   # the number of the next byte we are waiting for
        self.earlySegments = {}          # sequenceNumber -> payload, segments that arrived too early
        self.orderedBytes = bytearray()  # the bytes in the right order, not yet parsed

    def addSegment(self, sequenceNumber, payload, isSyn):
        if isSyn:
            # The SYN counts as one byte, so the data starts right after it
            self.nextSequenceNumber = sequenceNumber + 1
            return
        if self.nextSequenceNumber is None:
            # We started sniffing in the middle of the connection: start from here
            self.nextSequenceNumber = sequenceNumber

        isAlreadyReceived = sequenceNumber < self.nextSequenceNumber   # a retransmission
        if payload and not isAlreadyReceived:
            self.earlySegments[sequenceNumber] = payload
        self.moveSegmentsThatFit()

    def moveSegmentsThatFit(self):
        while self.nextSequenceNumber in self.earlySegments:
            payload = self.earlySegments.pop(self.nextSequenceNumber)
            self.orderedBytes += payload
            self.nextSequenceNumber += len(payload)


@dataclass
class HttpMessage:
    startLine: str    # "POST /orders HTTP/1.1" for a request, "HTTP/1.1 201 Created" for a response
    headers: list     # [(name, value)] in the order they were sent
    body: bytes = b""

    def isRequest(self):
        return not self.startLine.startswith("HTTP/")

    # Only well-formed start lines get this far (takeNextMessage checks), but never fail on one: "" when missing
    def getMethod(self):
        return self.startLine.split(" ")[0]

    def getUri(self):
        parts = self.startLine.split(" ")
        return parts[1] if len(parts) > 1 else ""

    def getStatusCode(self):
        parts = self.startLine.split(" ")
        return parts[1] if len(parts) > 1 else ""

    def getHeader(self, name, defaultValue=""):
        for headerName, headerValue in self.headers:
            if headerName.lower() == name.lower():
                return headerValue
        return defaultValue

    def getBodyAsJson(self):
        """The body as parsed JSON, the text when it isn't JSON, None when there is no body."""
        if not self.body:
            return None
        try:
            return json.loads(self.body)
        except ValueError:
            return self.body.decode("utf-8", "replace")

    def getBodyAsText(self):
        """Pretty JSON when the body is JSON, plain text otherwise."""
        try:
            return json.dumps(json.loads(self.body), indent=2)
        except ValueError:
            return self.body.decode("utf-8", "replace")


class HttpParser:

    @staticmethod
    def takeNextMessage(stream):
        """Removes one complete message from the front of the stream and returns it, or None if it isn't all there yet."""
        data = bytes(stream.orderedBytes)
        headerEnd = data.find(HEADER_END)
        if headerEnd == -1:
            return None

        message = HttpParser.parseHead(data[:headerEnd])
        # Bytes that only looked like HTTP (another protocol, or a stream joined in the middle): not a message
        if not (REQUEST_LINE.fullmatch(message.startLine) or STATUS_LINE.fullmatch(message.startLine)):
            raise ValueError(f"not an HTTP start line: {message.startLine[:60]!r}")
        bodyStart = headerEnd + len(HEADER_END)
        if "chunked" in message.getHeader("Transfer-Encoding").lower():
            bodyAndEnd = HttpParser.readChunkedBody(data, bodyStart)
        else:
            contentLength = int(message.getHeader("Content-Length", "0"))
            bodyAndEnd = HttpParser.readFixedLengthBody(data, bodyStart, contentLength)
        if bodyAndEnd is None:
            return None

        message.body, messageEnd = bodyAndEnd
        del stream.orderedBytes[:messageEnd]
        return message

    @staticmethod
    def parseHead(head):
        lines = head.decode("iso-8859-1").split("\r\n")
        headers = []
        for line in lines[1:]:
            name, _, value = line.partition(":")
            headers.append((name.strip(), value.strip()))
        return HttpMessage(lines[0], headers)

    @staticmethod
    def readFixedLengthBody(data, bodyStart, contentLength):
        """(body, end position), or None if the body hasn't fully arrived."""
        bodyEnd = bodyStart + contentLength
        if len(data) < bodyEnd:
            return None
        return data[bodyStart:bodyEnd], bodyEnd

    @staticmethod
    def readChunkedBody(data, position):
        """The body comes in chunks: "<size in hex>\\r\\n<that many bytes>\\r\\n", and a chunk of size 0 ends it.
        Returns (body, end position), or None if the last chunk hasn't arrived yet."""
        body = b""
        while True:
            sizeLineEnd = data.find(LINE_END, position)
            if sizeLineEnd == -1:
                return None
            chunkSize = int(data[position:sizeLineEnd].split(b";")[0], 16)
            chunkStart = sizeLineEnd + len(LINE_END)
            chunkEnd = chunkStart + chunkSize
            if len(data) < chunkEnd + len(LINE_END):
                return None
            body += data[chunkStart:chunkEnd]
            position = chunkEnd + len(LINE_END)
            if chunkSize == 0:
                return body, position
