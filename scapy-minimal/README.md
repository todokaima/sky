# scapy-minimal (vanilla, standalone)

Sniffs HTTP with scapy, puts the TCP segments back together, pairs each request with its response, and lists them
on a page. Standalone: no Docker, no NetCanvas. It sniffs `lo` (this machine's own traffic, e.g. services started from
the IDE or `mvn spring-boot:run`, called on `localhost`), and the page is on port **29922**.

```bash
cd vanilla/scapy-minimal
sudo .venv/bin/python main.py          # every port on lo
# open http://localhost:29922/scapyminimal
```

It captures right away. On the page you can narrow it down and press **Apply**:

| Field | Example | Does |
|---|---|---|
| URI prefixes | `/orders, /health` | shows requests whose URI starts with any of these |
| Methods | `POST` | shows these methods |
| Server ports | `8085, 8086` | sniffs only these ports (empty: every port) |
| Client ports | `38270` | shows requests from these client ports |
| Interfaces | `lo, eth0` | where to sniff (empty: `lo`) |

The line at the top says what is being sniffed. **Show all** clears the URI, method and client port filters (the
server ports and interfaces stay). **Clear** empties the list, **Pause** stops refreshing. Click a request to see it
and its response. Traffic that isn't HTTP (Kafka, Postgres, TLS) is skipped.

The same fields can be given at start; the page can change them afterwards:

```bash
sudo .venv/bin/python main.py --dstPorts 8085 8086 --uris /orders
sudo .venv/bin/python main.py --config config.json
```

Options: `--uris`, `--methods`, `--dstPorts`, `--srcPorts`, `--interfaces` (each takes one value or several),
`--webPort` (default 29922), `--webHost` (default `127.0.0.1`), `--config`.

## Databases and Redis

The stores' own protocols are read too (`protocols.py`) and listed like requests, each with its reply:

| Row | Example | Reply |
|---|---|---|
| `REDIS` | `GET stock:Tea`, `AUTH ****` | `15`, `2 items: price, 2.20`, an error |
| `MONGO` | `shopdocs.find reviews {'product': 'Tea'}` | `2 documents`, `n = 1`, the error |
| `PGSQL` | the query | `SELECT 3`, the error's message |
| `MYSQL` | the query (MySQL and MariaDB) | `OK, 1 rows affected`, `result set, 4 columns`, `1064 …` |

On their usual ports (Redis 6379, MongoDB 27017, PostgreSQL 5432, MySQL 3306) and the testclient stack's
(2345, 2346, 3307, 3308). On `lo` that's what this machine sends them: the client's query cards, `redis-cli`,
`mongosh`, `psql`. The method and server port filters work on them; Edit & resend is for HTTP only. Kafka isn't
listed (its replies aren't read); logins, handshakes and pings are left out.

## API

- `GET /api/exchanges`: the captured requests that pass the filter, newest first.
- `GET /api/exchangesByPorts`: the same grouped by `"clientPort:serverPort"`, oldest first, bodies as parsed JSON.
- `GET`/`POST /api/filter`: `{"uris", "methods", "destinationPorts", "sourcePorts", "interfaces"}`; posting it also
  sniffs those server ports on those interfaces, and answers with `sniffing` and `errors`.
- `POST /api/clear`: empties the list. `GET /api/status`: what is being sniffed.

## Edit & resend

Open a captured request and press **Edit & resend**: the method, URI, target (`host:port`, the server as
captured; a container's IP works from this machine, or type `localhost:8085`), headers and body can all be
changed. **Resend** sends it over a new connection with `Content-Length` worked out again and shows the response
under the editor; the sniffer captures it like any other request. The editor stays open across refreshes.
`POST /api/resend` with `{"target", "method", "uri", "headers": [[name, value]], "body"}` does the same.

## Files

| File | What it does |
|---|---|
| `main.py` | reads the options, sniffs, starts the page |
| `capture.py` | packets → request/response exchanges, and the filter |
| `resend.py` | sends a captured request again, as edited on the page (`POST /api/resend`) |
| `httpstream.py` | bytes → HTTP: TCP reordering and HTTP message parsing |
| `server.py` | the page and the API |

Needs `scapy` (see `requirements.txt`): `python3 -m venv .venv && .venv/bin/pip install -r requirements.txt`.
