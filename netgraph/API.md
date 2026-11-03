# NetCanvas API: integration guide

NetCanvas is a small Spring Boot service that stores and draws a graph:

- **Nodes** are shown as tabs, for example one per service or port. They carry free-form JSON **metadata**.
- **Edges** are shown as spline links between tabs. They carry metadata and a stream of **messages**, typically captured **request/response exchanges**. The ✉ bubble on each link shows **only the latest exchange**.

The web UI at `/` re-fetches everything every 2 s (`netcanvas.poll-ms`), so whatever you send through this API appears on its own. There's nothing to push or notify.

An integrating service usually does two things:
1. **Build the graph:** make sure the nodes and links you want exist. See [Recommended sync algorithm](#recommended-sync-algorithm).
2. **Report traffic:** post each captured request and response onto its link. See [Reporting captures](#reporting-captures-requestresponse-pairing).

Basics:
- **Base URL:** `http://localhost:29994/api` (port set by `server.port` in `application.properties`, or the `PORT` env var)
- **Format:** JSON in and out. Send `Content-Type: application/json` on every request with a body.
- **Auth:** none. CORS is open to any origin.
- **Storage:** in memory. **A restart wipes everything** (nodes, links, messages), so your service should re-push the graph on startup or on a timer.

---

## Data model

### Node
```json
{ "id": "n1", "label": "port1", "type": "Service", "metadata": { "service": "orders-api", "port": 8080 }, "x": 120, "y": 340 }
```
| Field | Type | Notes |
|---|---|---|
| `id` | string | **Assigned by the server** (`n1`, `n2`, …). Any `id` you send is ignored. |
| `label` | string | Display name, and the easiest way to refer to a node (e.g. a port name). Defaults to `"<type> <number>"`. **Uniqueness is not enforced**, so keep labels unique. |
| `type` | string | Any string. It only sets the tab colour. Types in `netcanvas.types` (see `GET /api/config`) get their colour; others are grey. Defaults to the first configured type. |
| `metadata` | object | Any JSON object. Defaults to `{}`. |
| `x`, `y` | integer or null | Canvas position. **Leave these out**: the UI places new nodes next to their neighbours and saves positions itself. |

### Edge
```json
{
  "id": "e3", "from": "n1", "to": "n2", "label": "orders",
  "metadata": { "protocol": "http" },
  "messages": [
    { "at": "2026-10-27T20:15:02.114Z",
      "data": { "id": "stream-17",
                "request":  { "method": "POST", "path": "/orders", "body": { "item": "book", "qty": 2 } },
                "response": { "status": 201, "body": { "orderId": "A-1043" } } } }
  ]
}
```
| Field | Type | Notes |
|---|---|---|
| `id` | string | **Assigned by the server** (`e3`, …). Nodes and edges share one counter. |
| `from`, `to` | string | Node ids. On create you can pass a node **id or label**; the server stores the id. |
| `label` | string | Shown in the bubble while the link has no messages, and in the expanded header. Defaults to `""`. |
| `metadata` | object | What the link *is* (protocol, owner, limits…). Defaults to `{}`. |
| `messages` | array | What is *happening* on the link. **Oldest first**, so the last element is the latest. Each is `{"at": <ISO-8601 server time>, "data": <any JSON>}`. Only the newest `netcanvas.message-history` (default 20) are kept. Read-only in this object; add messages with the [message endpoints](#messages). |

**Request/response exchange.** A message whose `data` has a `request` (or `req`) and/or `response` (or `res`, `resp`) key is shown as an exchange. An optional `id` in `data` is the correlation id used for pairing.

Edge rules:
- **One edge per node pair, in either direction.** A second A→B or B→A returns **409**.
- **No self-links.** These return **400**.
- **Direction is only cosmetic.** Messages find their link in either direction.
- **Endpoints can't be changed.** Delete the edge and create a new one.
- **Deleting a node deletes its edges** (and their messages).

---

## Endpoints

`{ref}` = a node **id or label** (ids are checked first, then labels case-insensitively, first match wins; URL-encode labels with spaces). Edges are referenced **by id only**.

### Graph
| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/api/graph` | – | `200` `{"nodes":[Node…], "edges":[Edge…]}` |
| `DELETE` | `/api/graph` | – | `200`, empty. Removes all nodes, edges and messages. |
| `GET` | `/api/config` | – | `200` `{"title", "pollMs", "types":[{"name","color"}], "physics":{…}}` |

### Nodes
| Method | Path | Body | Response |
|---|---|---|---|
| `POST` | `/api/nodes` | `{label?, type?, metadata?, x?, y?}` | `201` the created Node |
| `GET` | `/api/nodes/{ref}` | – | `200` Node |
| `PATCH` | `/api/nodes/{ref}` | any of `{label, type, metadata, x, y}` | `200` updated Node |
| `DELETE` | `/api/nodes/{ref}` | – | `200`, empty (its edges go too) |

PATCH changes only the fields you send. **`metadata` here replaces the whole object**; use the metadata endpoint to merge. `x`/`y` may be `null`, which makes the UI lay the node out again. Don't send `label`/`type` as `null`, because they'd be stored as the string `"null"`.

### Edges
| Method | Path | Body | Response |
|---|---|---|---|
| `POST` | `/api/edges` | `{from, to, label?, metadata?}` (from/to = node id or label) | `201` the created Edge |
| `GET` | `/api/edges/{id}` | – | `200` Edge |
| `PATCH` | `/api/edges/{id}` | any of `{label, metadata}` | `200` updated Edge (`metadata` replaces) |
| `DELETE` | `/api/edges/{id}` | – | `200`, empty |
| `DELETE` | `/api/edges` | – | `200`, empty. Removes all edges and keeps the nodes. |

### Metadata (nodes and edges)
| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/api/nodes/{ref}/metadata`, `/api/edges/{id}/metadata` | – | `200` the metadata object |
| `PUT` | same | JSON object | `200` new metadata. **Replaces** everything. |
| `PATCH` | same | JSON object | `200` new metadata. **Merges**: sent keys are added or overwritten; a key sent as `null` is **removed**. |

```bash
curl -X PUT   localhost:29994/api/nodes/port1/metadata -H 'Content-Type: application/json' -d '{"service":"orders-api","port":8080}'
curl -X PATCH localhost:29994/api/nodes/port1/metadata -H 'Content-Type: application/json' -d '{"version":"1.4.2","port":null}'
```

### Messages
| Method | Path | Body | Response |
|---|---|---|---|
| `POST` | `/api/messages` | `{"from", "to", "request"?, "response"?, "id"?}`: an exchange event, **paired** (see below). **Or** `{"from", "to", "data"}`: any other message, stored as-is. `from`/`to` = node id or label. | `201` the stored/updated message `{"at", "data"}`. `404` if a node is unknown or the two nodes aren't linked. |
| `POST` | `/api/edges/{id}/exchange` | `{"request"?, "response"?, "id"?}` | same pairing, by edge id |
| `POST` | `/api/edges/{id}/messages` | any JSON value (string, number, object) | `201` `{"at", "data"}`. Stored as-is, **no pairing**. |
| `GET` | `/api/edges/{id}/messages` | – | `200` list, oldest first |
| `DELETE` | `/api/edges/{id}/messages` | – | `200`, empty (clears the history) |

### API docs (what a capture draws)
| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/api/apidocs` | – | `200` `{"8085": <OpenAPI JSON>, …}` |
| `PUT` | `/api/apidocs/{port}` | a service's `/v3/api-docs` JSON | `200` the same. `400` if the port isn't a number or there are no `paths` |
| `POST` | `/api/apidocs/fetch` | `{"url": "http://localhost:8085/v3/api-docs"}` | `200` that api-docs, fetched by the server (not stored: the page then `PUT`s it). `localhost` there means `netcanvas.apidocs-host`. `502` if it can't |
| `DELETE` | `/api/apidocs/{port}` | – | `200`, empty |

Pasted on the page under **Capture endpoints**. scapy-minimal reads them when a capture starts and draws only the
requests to those endpoints. `DELETE /api/graph` (the **Reset** button) keeps them.

### Errors
Spring's standard error body. `message` says why:
```json
{ "timestamp": "…", "status": 404, "error": "Not Found", "message": "no link between port1 and port3", "path": "/api/messages" }
```
| Status | When |
|---|---|
| `400` | self-link, invalid JSON, or a non-object where an object is expected |
| `404` | unknown node (including `from`/`to`), unknown edge id, `POST /api/messages` between nodes that aren't linked |
| `409` | an edge between those two nodes already exists (either direction) |

---

## Reporting captures (request/response pairing)

A capture usually reports **the request and the response as two separate events** for a port pair. The response often travels the other way (port2 → port1). Post each event to `POST /api/messages` as it arrives, and the server pairs them into one exchange on the link:

| You send | What happens |
|---|---|
| `{"from":"port1","to":"port2","request":{…}}` | **Starts a new exchange** on the port1–port2 link, waiting for its response. |
| `{"from":"port2","to":"port1","response":{…}}` | **Completes the newest waiting exchange** on that link (either direction). It becomes the latest message, and its `at` is set to the response time. |
| `"id"` on both events | **Correlation.** The response completes the waiting request **with the same `id`**. Use it whenever requests can overlap (concurrent streams, pipelining). If the response has an `id` and no waiting request has that `id`, it's stored as an orphan. |
| `response` with no waiting request | Stored on its own as an orphan, shown as `… → 201`. |
| `request` and `response` together | Stored as a complete exchange in one call. |

Each new request is a new exchange. Older ones roll off after `netcanvas.message-history` (default 20). In the UI you step through them with ‹ ›.

> **Don't store exchanges in edge metadata or the edge label.** Keys like `"1. POST /orders → 201"` in metadata show up as one long list and can't be browsed. Post every request and response to `/api/messages` instead. Keep metadata for static facts about the link.

**Recommended shape.** Any JSON works inside `request`/`response`, but these fields make the bubble's summary line useful:

```json
"request":  { "method": "POST", "path": "/orders", "headers": { … }, "body": { … } }
"response": { "status": 201, "headers": { … }, "body": { … } }
```
- **Summary line:** built from `method`, `path` (or `url`, `endpoint`) and `status` (or `code`, `statusText`), e.g. `POST /orders → 201`.
- **Status colour:** a `status`/`code` of 400 or higher is marked red.

```bash
# request captured on port1 → port2
curl -X POST localhost:29994/api/messages -H 'Content-Type: application/json' -d '{
  "from":"port1", "to":"port2", "id":"stream-17",
  "request": {"method":"POST","path":"/orders","body":{"item":"book","qty":2}} }'

# response captured on port2 → port1: completes the exchange above
curl -X POST localhost:29994/api/messages -H 'Content-Type: application/json' -d '{
  "from":"port2", "to":"port1", "id":"stream-17",
  "response": {"status":201,"body":{"orderId":"A-1043"}} }'
```

**Prerequisite:** both ports must already exist as nodes (e.g. labelled `port1`, `port2`, or `8080`, `5432`) **and be linked**. Otherwise `/api/messages` returns `404`. Create them first (next section), or create a node and link the first time a new port pair shows up in a capture.

### What the UI shows
- **Collapsed bubble:** `✉ POST /orders → 201 · 3s`. It shows `POST /orders → …` while the response is pending, and flashes when a new request or response arrives.
- **Expanded bubble** (click it):
  - **Header:** `port1 → port2` and the link label.
  - **One exchange at a time:** the latest by default. When there are several, **‹ ›** buttons (or ← → keys) step through them, with a `2 / 4 · latest` counter.
  - **Request and Response blocks:** red for ≥ 400, "waiting for response…" while pending, plus a *Copy message* button.
  - **Link metadata:** editable on the bubble.
- **Navigable:** text inside expanded tabs and bubbles is selectable, and long JSON scrolls.

---

## Building a network: quick example

```bash
B=localhost:29994/api; H='Content-Type: application/json'

curl -X POST $B/nodes -H "$H" -d '{"label":"port1","type":"External","metadata":{"service":"web-client"}}'
curl -X POST $B/nodes -H "$H" -d '{"label":"port2","type":"Service","metadata":{"service":"orders-api","port":8080}}'
curl -X POST $B/nodes -H "$H" -d '{"label":"port3","type":"Data","metadata":{"service":"postgres","port":5432}}'

curl -X POST $B/edges -H "$H" -d '{"from":"port1","to":"port2","label":"HTTP"}'
curl -X POST $B/edges -H "$H" -d '{"from":"port2","to":"port3","label":"SQL"}'
```
Within about 2 s the UI shows three tabs, laid out automatically, joined by two links ready to receive messages.

---

## Recommended sync algorithm

`POST /api/nodes` and `POST /api/edges` aren't idempotent: sending the same node twice creates two nodes. A service that owns "the network we want" should **reconcile** against the current state:

```
desired = nodes keyed by a unique label, edges as (fromLabel, toLabel, label, metadata)

1. current = GET /api/graph
   byLabel = { node.label: node for node in current.nodes }

2. For each desired node:
     if label not in byLabel:   n = POST /api/nodes {label, type, metadata};  byLabel[label] = n
     else:
       n = byLabel[label]
       if n.type != type:          PATCH /api/nodes/{n.id} {"type": type}
       if n.metadata != metadata:  PUT   /api/nodes/{n.id}/metadata  metadata

3. pairKey(a, b) = sorted pair of node ids   (edges are unique per unordered pair)
   existing = { pairKey(e.from, e.to): e for e in current.edges }
   For each desired edge:
     a, b = byLabel[fromLabel].id, byLabel[toLabel].id
     e = existing.get(pairKey(a, b))
     if e is None:  POST /api/edges {from: a, to: b, label, metadata}
     else:
       if e.label != label:        PATCH /api/edges/{e.id} {"label": label}
       if e.metadata != metadata:  PUT   /api/edges/{e.id}/metadata  metadata

4. (Optional, if your service owns the whole graph) delete what isn't desired:
     DELETE /api/edges/{id}, DELETE /api/nodes/{id}
```

Notes:
- **Leave positions to the UI.** Don't send `x`/`y`, so nodes the user dragged keep their place across syncs.
- **Run it on startup and on a timer, or after changes.** It's safe to repeat and recovers after a NetCanvas restart. Note that a restart also clears messages; they reappear as new captures come in.
- **Don't reconcile messages.** Just `POST` them as captures happen.
- **Quick full reset.** `DELETE /api/graph` and then recreate also works, but it re-lays-out everything and loses user-arranged positions.
- **No bulk endpoints.** Send one request per item. That's fine for hundreds of nodes and links and a steady stream of captures.

---

## Minimal client (Python, `requests`)

```python
import requests

API = "http://localhost:29994/api"

def sync(nodes, edges):
    """nodes: [{"label", "type", "metadata"}]; edges: [{"from", "to", "label", "metadata"}] with from/to = labels."""
    g = requests.get(f"{API}/graph").json()
    by_label = {n["label"]: n for n in g["nodes"]}

    for n in nodes:
        cur = by_label.get(n["label"])
        if cur is None:
            by_label[n["label"]] = requests.post(f"{API}/nodes", json=n).json()
            continue
        if n.get("type") and cur["type"] != n["type"]:
            requests.patch(f"{API}/nodes/{cur['id']}", json={"type": n["type"]})
        if cur["metadata"] != n.get("metadata", {}):
            requests.put(f"{API}/nodes/{cur['id']}/metadata", json=n.get("metadata", {}))

    pair = lambda a, b: tuple(sorted((a, b)))
    existing = {pair(e["from"], e["to"]): e for e in g["edges"]}
    for e in edges:
        a, b = by_label[e["from"]]["id"], by_label[e["to"]]["id"]
        cur = existing.get(pair(a, b))
        if cur is None:
            requests.post(f"{API}/edges", json={**e, "from": a, "to": b}).raise_for_status()
            continue
        if cur["label"] != e.get("label", ""):
            requests.patch(f"{API}/edges/{cur['id']}", json={"label": e.get("label", "")})
        if cur["metadata"] != e.get("metadata", {}):
            requests.put(f"{API}/edges/{cur['id']}/metadata", json=e.get("metadata", {}))


def report_request(src_port, dst_port, request, stream_id=None):
    """Call when a capture yields a request (src → dst)."""
    body = {"from": src_port, "to": dst_port, "request": request}
    if stream_id is not None:
        body["id"] = stream_id
    requests.post(f"{API}/messages", json=body).raise_for_status()


def report_response(src_port, dst_port, response, stream_id=None):
    """Call when a capture yields a response (usually dst → src); it completes the matching request."""
    body = {"from": src_port, "to": dst_port, "response": response}
    if stream_id is not None:
        body["id"] = stream_id
    requests.post(f"{API}/messages", json=body).raise_for_status()


# Example
sync(
    nodes=[{"label": "port1", "type": "External"}, {"label": "port2", "type": "Service", "metadata": {"port": 8080}}],
    edges=[{"from": "port1", "to": "port2", "label": "HTTP"}],
)
report_request("port1", "port2", {"method": "POST", "path": "/orders", "body": {"qty": 2}}, stream_id="s-17")
report_response("port2", "port1", {"status": 201, "body": {"orderId": "A-1043"}}, stream_id="s-17")
```

---

## Limits to be aware of
- **Everything is in memory:** a restart clears nodes, links and messages.
- **Polling, not push:** the UI updates within `poll-ms` (default 2 s). Simultaneous edits are last-write-wins.
- **Label lookups:** labels aren't enforced unique, and lookups by label take the first match.
- **Open API:** no auth, open CORS. Keep it on a trusted network.
