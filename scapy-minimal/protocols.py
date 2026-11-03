"""
The stores' own protocols, read off one TCP payload at a time, enough to say what was asked and what came back:

  Redis       RESP arrays of bulk strings: "GET stock:Tea" -> "$2 15"
  PostgreSQL  Query / Parse messages: the SQL -> CommandComplete "SELECT 3", or the error's message
  MySQL       COM_QUERY / COM_STMT_PREPARE: the SQL -> OK, ERR (code, message) or a result set
  MongoDB     OP_MSG: the command and its collection ("find reviews") -> ok, n, how many documents came back
  Kafka       the request's API (Produce, ListOffsets; the constant Fetch / Heartbeat / Metadata are left out)

Each read gives a list of ("request" | "response", summary dict, correlation) for the message(s) in that payload.
A message split over two segments isn't put back together (they're small: commands and their first reply); what
can't be read is skipped. Housekeeping (auth, handshakes, pings, hellos) is left out.
"""

import struct

# The server ports: the containers' own (what the Docker bridges see) and the testclient stack's published ones
STORE_PORTS = {5432: "postgres", 2345: "postgres", 2346: "postgres",
               3306: "mysql", 3307: "mysql", 3308: "mysql",
               27017: "mongodb",
               6379: "redis",
               9092: "kafka", 29092: "kafka"}


def storeOf(port):
    return STORE_PORTS.get(port)


def shorten(text, limit=160):
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[:limit - 1] + "…"


# ---- Redis ----

def _respValue(data, at):
    """One RESP value from data[at:] -> (value, next position); raises ValueError / IndexError if incomplete."""
    kind = data[at:at + 1]
    end = data.index(b"\r\n", at)
    line = data[at + 1:end]
    at = end + 2
    if kind in (b"+", b"-", b":", b"_", b",", b"#", b"("):
        return (kind.decode() + line.decode("utf-8", "replace")), at
    if kind in (b"$", b"=", b"!"):
        length = int(line)
        if length < 0:
            return None, at
        if len(data) < at + length + 2:
            raise ValueError("incomplete bulk string")
        return data[at:at + length].decode("utf-8", "replace"), at + length + 2
    if kind in (b"*", b"~", b">", b"%"):
        count = int(line)
        if count < 0:
            return None, at
        items = []
        for _ in range(count * (2 if kind == b"%" else 1)):
            item, at = _respValue(data, at)
            items.append(item)
        return items, at
    raise ValueError("not RESP")


def readRedis(payload, toServer):
    out, at = [], 0
    while at < len(payload):
        try:
            value, at = _respValue(payload, at)
        except (ValueError, IndexError):
            break
        if toServer:
            if not isinstance(value, list) or not value:
                continue
            command = str(value[0]).upper()
            if command in ("AUTH", "HELLO"):
                out.append(("request", {"method": "REDIS", "path": command + " ****"}, None))
                continue
            out.append(("request", {"method": "REDIS", "path": shorten(" ".join(map(str, [command, *value[1:]])))}, None))
        else:
            if isinstance(value, list):
                text = f"{len(value)} items: " + shorten(", ".join(map(str, value[:8])), 120)
            else:
                text = "(nil)" if value is None else shorten(value, 120)
            failed = isinstance(value, str) and value.startswith("-")
            out.append(("response", {"status": "ERR" if failed else "OK", "reply": text}, None))
    return out


# ---- PostgreSQL ----

def readPostgres(payload, toServer):
    out, at = [], 0
    # A startup, SSL or cancel request has no type byte: protocol version / magic numbers after the length
    if toServer and len(payload) >= 8 and struct.unpack(">I", payload[4:8])[0] in (196608, 80877103, 80877102, 80877104):
        return out
    while at + 5 <= len(payload):
        kind = payload[at:at + 1]
        length = struct.unpack(">I", payload[at + 1:at + 5])[0]
        body = payload[at + 5:at + 1 + length]
        at += 1 + length
        if length < 4 or len(body) < length - 4:
            break
        if toServer:
            if kind == b"Q":
                out.append(("request", {"method": "SQL", "path": shorten(body.rstrip(b"\x00").decode("utf-8", "replace"))}, None))
            elif kind == b"P":
                name, _, rest = body.partition(b"\x00")
                query = rest.split(b"\x00", 1)[0].decode("utf-8", "replace")
                out.append(("request", {"method": "SQL", "path": shorten(query)}, None))
        else:
            if kind == b"C":
                out.append(("response", {"status": "OK", "reply": body.rstrip(b"\x00").decode("utf-8", "replace")}, None))
            elif kind == b"E":
                fields = {f[:1]: f[1:].decode("utf-8", "replace") for f in body.split(b"\x00") if f}
                out.append(("response", {"status": "ERR", "reply": shorten(fields.get(b"M", "error"))}, None))
    # Extended queries with a statement prepared before: Bind/Execute without a Parse
    if toServer and not out and payload[:1] == b"B":
        out.append(("request", {"method": "SQL", "path": "(prepared statement)"}, None))
    return out


# ---- MySQL / MariaDB ----

MYSQL_COMMANDS = {0x03: "SQL", 0x16: "PREPARE", 0x17: "EXECUTE", 0x02: "USE"}


def readMysql(payload, toServer):
    out, at = [], 0
    while at + 5 <= len(payload):
        length = payload[at] | payload[at + 1] << 8 | payload[at + 2] << 16
        sequence = payload[at + 3]
        body = payload[at + 4:at + 4 + length]
        at += 4 + length
        if len(body) < length or not body:
            break
        if toServer:
            # A command starts its own sequence at 0; the login (sequence 1) isn't one
            if sequence == 0 and body[0] in MYSQL_COMMANDS:
                text = "(prepared statement)" if body[0] == 0x17 else body[1:].decode("utf-8", "replace")
                out.append(("request", {"method": MYSQL_COMMANDS[body[0]], "path": shorten(text)}, None))
        elif sequence == 1:
            # The first packet of the answer says what it is
            if body[0] == 0x00:
                out.append(("response", {"status": "OK", "reply": f"OK, {body[1] if len(body) > 1 else 0} rows affected"}, None))
            elif body[0] == 0xFF:
                code = struct.unpack("<H", body[1:3])[0] if len(body) >= 3 else 0
                message = body[9:].decode("utf-8", "replace") if body[3:4] == b"#" else body[3:].decode("utf-8", "replace")
                out.append(("response", {"status": "ERR", "reply": shorten(f"{code} {message}")}, None))
            elif body[0] < 0xFB:
                out.append(("response", {"status": "OK", "reply": f"result set, {body[0]} columns"}, None))
    return out


# ---- MongoDB ----

def _cstring(data, at):
    end = data.index(b"\x00", at)
    return data[at:end].decode("utf-8", "replace"), end + 1


def _bson(data, at=0, depth=0):
    """A BSON document at data[at:] -> dict (arrays as lists); values of other types come out as None."""
    size = struct.unpack("<i", data[at:at + 4])[0]
    end, at, doc = at + size, at + 4, {}
    while at < end - 1:
        kind = data[at]
        name, at = _cstring(data, at + 1)
        if kind == 0x01:
            value, at = struct.unpack("<d", data[at:at + 8])[0], at + 8
        elif kind in (0x02, 0x0D, 0x0E):
            length = struct.unpack("<i", data[at:at + 4])[0]
            value, at = data[at + 4:at + 3 + length].decode("utf-8", "replace"), at + 4 + length
        elif kind in (0x03, 0x04):
            length = struct.unpack("<i", data[at:at + 4])[0]
            value = _bson(data, at, depth + 1) if depth < 4 else {}
            if kind == 0x04:
                value = list(value.values())
            at += length
        elif kind == 0x05:
            length = struct.unpack("<i", data[at:at + 4])[0]
            value, at = None, at + 5 + length
        elif kind == 0x07:
            value, at = data[at:at + 12].hex(), at + 12
        elif kind == 0x08:
            value, at = data[at] == 1, at + 1
        elif kind in (0x09, 0x11, 0x12):
            value, at = struct.unpack("<q", data[at:at + 8])[0], at + 8
        elif kind in (0x0A, 0x06, 0xFF, 0x7F):
            value = None
        elif kind == 0x10:
            value, at = struct.unpack("<i", data[at:at + 4])[0], at + 4
        elif kind == 0x13:
            value, at = None, at + 16
        else:
            break                     # a type we don't read: stop here, keep what we have
        doc[name] = value
    return doc


MONGO_HOUSEKEEPING = {"hello", "ismaster", "isMaster", "ping", "saslStart", "saslContinue", "buildInfo", "buildinfo",
                      "endSessions", "getParameter", "getLog", "atlasVersion", "getCmdLineOpts", "hostInfo",
                      "getFreeMonitoringStatus", "connectionStatus"}


def readMongo(payload, toServer):
    out, at = [], 0
    while at + 21 <= len(payload):
        length, requestId, responseTo, opCode = struct.unpack("<iiii", payload[at:at + 16])
        message = payload[at:at + length]
        at += length
        if length < 21 or len(message) < length or opCode != 2013:
            break
        try:
            # flags, then the body section (kind 0): one document
            if message[20] != 0:
                continue
            doc = _bson(message, 21)
        except (struct.error, ValueError, IndexError):
            continue
        if toServer:
            command = next(iter(doc), "")
            if command in MONGO_HOUSEKEEPING or not command:
                continue
            target = doc.get(command)
            what = f"{command} {target}" if isinstance(target, str) else command
            database = doc.get("$db", "")
            detail = doc.get("filter") or doc.get("pipeline") or ""
            path = f"{database}.{what}" if database else what
            out.append(("request", {"method": "MONGO", "path": shorten(path + (f" {detail}" if detail else ""))}, requestId))
        else:
            if responseTo == 0:
                continue
            ok = doc.get("ok") == 1
            cursor = doc.get("cursor") if isinstance(doc.get("cursor"), dict) else {}
            batch = cursor.get("firstBatch", cursor.get("nextBatch"))
            reply = (doc.get("errmsg") or "error") if not ok else \
                f"{len(batch)} documents" if isinstance(batch, list) else \
                f"n = {doc['n']}" if "n" in doc else "ok"
            out.append(("response", {"status": "OK" if ok else "ERR", "reply": shorten(reply)}, responseTo))
    return out


# ---- Kafka ----

KAFKA_APIS = {0: "Produce", 2: "ListOffsets", 19: "CreateTopics", 20: "DeleteTopics", 32: "DescribeConfigs"}


def readKafka(payload, toServer):
    # Only the requests worth drawing; Fetch, Metadata, heartbeats and the group's calls run all the time
    if not toServer or len(payload) < 12:
        return []
    size, apiKey, version, correlation = struct.unpack(">ihhi", payload[:12])
    if apiKey not in KAFKA_APIS:
        return []
    clientLength = struct.unpack(">h", payload[12:14])[0] if len(payload) >= 14 else -1
    client = payload[14:14 + clientLength].decode("utf-8", "replace") if clientLength > 0 else ""
    return [("request", {"method": "KAFKA", "path": f"{KAFKA_APIS[apiKey]} v{version}" + (f" ({client})" if client else "")},
             None)]


READERS = {"redis": readRedis, "postgres": readPostgres, "mysql": readMysql, "mongodb": readMongo, "kafka": readKafka}


def read(store, payload, toServer):
    try:
        return READERS[store](bytes(payload), toServer)
    except (struct.error, ValueError, IndexError, KeyError):
        return []
