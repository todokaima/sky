# scapy-netgraph

**Headless by default.** It only sniffs and draws the client's captures in NetCanvas: its port (8000, on 127.0.0.1)
answers just `GET`/`POST /api/capture`, `POST /api/capture/stop` (the client's Send / Run / Consume) and
`GET /api/status` (what it sniffs and how far the traffic gets). `--page` brings back the page, the list, the filter
and Edit & resend on http://localhost:8000; the page to look at is [scapy-minimal](../scapy-minimal)'s
(http://localhost:29922/scapyminimal). `../run.sh` starts both, this one in the background (`logs/scapy-netgraph.log`).


Sniffs HTTP with scapy, puts the TCP segments back together, pairs each request with its response, and lists them
on a page, so you can see what is reaching the server and what it answers.

```bash
cd ~/Desktop/scapy-http/scapy-minimal
sudo ../scapy-http/.venv/bin/python main.py            # the ports pasted in NetCanvas, on every Docker bridge
# open http://localhost:8000
```

The page has a filter bar (URI prefixes, methods, server and client ports) that applies right away, also to what
was already captured. Click a row to see the request and the response. The options below set the filter the page
starts with (each one is optional, and an exchange must pass all the filters). Every option except `--webPort` takes
one value or several, under either name: `--dstPort 8085`, `--dstPorts 8085 8080`, `--dstPorts 8085,8080`, or
`--dstPort 8085 --dstPort 8080`.

| Option | Example | Keeps |
|---|---|---|
| `--uri`, `--uris` | `--uris /orders /health` | requests whose URI starts with any of these |
| `--method`, `--methods` | `--methods GET POST` | these methods |
| `--dstPort`, `--dstPorts` | `--dstPorts 8085 8080` | sniff only these server ports (default: every TCP port, so the page lists all the HTTP it sees on the bridges) |
| `--srcPort`, `--srcPorts` | `--srcPort 38270` | requests from any of these client ports |
| `--interface`, `--interfaces` | `--interfaces lo docker0` | where to sniff (default: every Docker bridge, `br-…` and `docker0`; `lo` if there are none) |
| `--service`, `--services` | `--services 8085=service1 8086=service2` | names for the nodes in NetCanvas (default: the port, e.g. `8085`); these ports are also sniffed from the start |
| `--webPort` | `--webPort 9000` | where the page is (default 8000) |
| `--webHost` | `--webHost 0.0.0.0` | the address the page listens on (default `127.0.0.1`, this machine only; `0.0.0.0`: every address, so a client in a container or on another machine can start captures). Anyone who reaches it sees the captured traffic |
| `--graphUrl` | `--graphUrl none` | NetCanvas's API (default `http://localhost:29994/api`; `none`: don't draw) |

The same settings can go in a JSON file, each one a single value or a list; anything given on the command line
overrides the file:

```bash
sudo ../.venv/bin/python main.py --config config.json
sudo ../.venv/bin/python main.py --config config.json --dstPort 8080   # the file, but port 8080
```

```json
{"uris": ["/orders", "/health"], "methods": "POST", "dstPorts": [8085, 8080], "interfaces": "lo", "webPort": 8000}
```

`GET /api/exchanges` returns the same list as JSON, newest first (each with `captured`: drawn in NetCanvas). `GET /api/exchangesByPorts` returns them grouped by
`"clientPort:serverPort"`, each a list (oldest first) of `{method, uri, request, statusCode, response}` with the
bodies as parsed JSON; the box at the top of the page shows it, with Copy and Download. `GET`/`POST /api/filter` reads or replaces the
filter, and `POST /api/clear` empties the list. `GET /api/capture` says whether a capture is running and with
which endpoints, `POST /api/capture` starts one (see below) and `POST /api/capture/stop` stops it.

## Captures

Only a capture is drawn in NetCanvas, and only the endpoints you gave it:

1. In NetCanvas, under **Capture endpoints**, say what to draw, by port:
   - just the ports (`8085, 8086`), box empty: every request on them;
   - api-docs URLs in the box, one per line (`http://localhost:8085/v3/api-docs`): NetCanvas fetches them, and each
     goes on its own port (or on the ports you typed);
   - endpoints in the box with the ports typed: `/orders` (any method), `POST /orders`, `GET /orders/{id}` (`{id}`:
     any one segment), `/orders/**` (anything under it);
   - or a pasted api-docs JSON, with the ports typed.

   Each is drawn as a node named after it (`8085`, or its `--service` name). NetCanvas keeps them
   (`GET /api/apidocs`) until it restarts. With nothing pasted, a capture draws every request it sees (but
   `/actuator/...` and `/v3/api-docs`).
2. **The client's actions capture.** **Send**, **Run** and **Consume** call `POST /api/capture` (`{"by": "Send on
   HTTP 1"}`) before their chain runs and `POST /api/capture/stop` (`{"graceSeconds": 1}`) a second after it ends.
   So NetCanvas draws what that action caused, and only that; the page says which action captured and when. Each
   capture reads the pasted docs again, empties the list and draws every request to one of those endpoints (method
   and path, `{processingId}` matching any one segment, the query string ignored) and its response. The page's
   **Capture** / **Stop** buttons do the same by hand.

The page lists everything it sniffs, captured or not (every TCP port on the Docker bridges by default, non-HTTP
traffic like Kafka and Postgres skipped), and its filter (**Apply**) works on that list right away.

Anything else (other endpoints, requests outside a capture) is still listed on the page, but not drawn; drawn ones
have a green edge. To start over on an empty graph, press **Reset** in NetCanvas (the pasted docs stay), then Send
again.

## Databases, Redis and Kafka

Their traffic is read too (`protocols.py`), and while a capture runs it's drawn in NetCanvas, each store its own
node (a database icon), named after its container (`service1-db:5432`, `test-redis:6379`, `docs-db:27017`):

| Store | Drawn | Example |
|---|---|---|
| Redis | each command and its reply | `GET stock:Tea → 15`, `AUTH ****` |
| PostgreSQL | each query and its CommandComplete, or the error | `SELECT … FROM orders … → SELECT 1` |
| MySQL / MariaDB | each query and OK / ERR / result set | `SELECT … FROM customers … → result set, 2 columns` |
| MongoDB | the command, collection and filter, and how many documents came back | `shopdocs.find reviews {'product': 'Tea'} → 2 documents` |
| Kafka | Produce and ListOffsets (Fetch, heartbeats and metadata run all the time, so they're left out) | `Produce v9 (producer-1)` |

Who asked: a service's node when its IP is known from HTTP (`8085` → `service1-db:5432`), the container's name, or
`host` for this machine (the client's psql / mysql / mongosh / redis-cli reach the stores through Docker's published
ports). Names come from `docker inspect`, every 15 s. Handshakes, logins, pings and hellos are left out; a message
split over two TCP segments isn't put back together, so a very long query may be missed.

## Where it stops

Every 15 s (when it changed) the terminal, and the page under its capture line, say how far the traffic got:

    seen 1840 packets (612 with data) · HTTP 40 requests, 6 captured · stores 95 commands, 14 captured

No packets: the interface (or the BPF filter) sees nothing. Packets but no HTTP requests: they're not HTTP/1.x, or
the stream was joined in the middle. Requests but none captured: no capture ran (the client's Send / Run / Consume,
or the page's Capture), or they're not one of the endpoints pasted in NetCanvas.

## The graph in NetCanvas

`graph.py` posts every captured request, and then its response, to NetCanvas (`../netgraph`, started
separately) as they happen, with `POST /api/messages`; NetCanvas pairs them by `id`, puts a ✉ on the middle of the
edge (click it for the exchanges, ‹ › to step through them) and keeps the history. Every port is a node: each
specified one (pasted in NetCanvas, or `--service`) as soon as a capture starts, and the destination and source port
of every captured request, e.g. `51000` for a curl or the client calling from that port. The first time a pair talks, NetCanvas answers 404 (not linked), so
the missing nodes and the edge are created and the message is sent again. It follows the page's filter. If
NetCanvas isn't running it says so once in the terminal, and those messages are dropped.

Who called is worked out from the IPs: a service's IP is learned from the requests it receives, so a request from that
IP was sent by that service. With the services in Docker Compose (`~/Desktop/testclient`), their traffic is on the
compose network's bridge, never on `lo`: your curls (forwarded by Docker from the bridge's gateway IP, so they show as
their source port) and the calls between the services. That's why the default is to sniff every Docker bridge, with the
kernel passing only the pasted ports. A `docker compose down`/`up` gives the network a new bridge; the
bridges are checked every 3 seconds and the sniffer moves to the new ones, so it keeps working.

Running the services from the IDE instead (`mvn spring-boot:run`, on the host)? Then sniff `lo`:
`--interface lo`. There everything is 127.0.0.1, so every caller shows as its source port. Don't sniff `lo` and the
bridges together: each curl would show up twice (curl → Docker's proxy on `lo`, the proxy → the service on the
bridge).

## Edit & resend

Open a captured request and press **Edit & resend**: the method, URI, target (`host:port`, the server as
captured; a container's IP works from this machine, or type `localhost:8085`), headers and body can all be
changed. **Resend** sends it over a new connection with `Content-Length` worked out again and shows the response
under the editor; the sniffer captures it like any other request. The editor stays open across refreshes.
`POST /api/resend` with `{"target", "method", "uri", "headers": [[name, value]], "body"}` does the same.

## Files

| File | What it does |
|---|---|
| `main.py` | reads the options, starts the sniffer and the web server |
| `capture.py` | packets → request/response exchanges, the filter, and what a capture records |
| `apidocs.py` | the services' endpoints, from their `/v3/api-docs` pasted in NetCanvas |
| `resend.py` | sends a captured request again, as edited on the page (`POST /api/resend`) |
| `httpstream.py` | bytes → HTTP: TCP reordering and HTTP message parsing, no scapy |
| `server.py` | the page, `/api/exchanges` and `/api/capture` |
| `graph.py` | posts each request and response to NetCanvas, between the services' nodes |
| `config.json` | an example config file |

Uses the parent project's venv (`scapy` only, see `requirements.txt`).
