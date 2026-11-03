# client

A small Spring Boot (Java 21, Maven, Thymeleaf) workbench at `/client`:

- **HTTP cards**: send requests server side, with headers from linked HTTP configs.
- **HTTP configs** have fields, like the DB ones: Accept, Content-Type and **Bearer token** (paste just the JWT;
  spaces, line breaks, quotes and a pasted `Bearer ` are dropped, it's sent as `Authorization: Bearer <token>`), plus
  a box for any other headers. **decode** shows the token's header and payload as JSON (the signature isn't checked),
  and the line under the field says when it expires. Older configs' header lines move into the fields on load.
- **Query cards**: optional python / java first (`run(var=...)` per execution, `set(...)` to pass values on), then
  the card's text against its linked DB config, by the config's **type**, in a one-shot container:

  | type | the card's text | url | client |
  |---|---|---|---|
  | PostgreSQL | SQL (schema: the search_path) | `jdbc:postgresql://localhost:2345/testdb` | `psql` (`postgres:alpine`) |
  | MySQL / MariaDB | SQL (schema: another database) | `jdbc:mysql://localhost:3307/loyalty`, `jdbc:mariadb://…` | `mysql` (`mysql:8.4`) |
  | MongoDB | a mongosh expression, e.g. `db.reviews.find({ product: "$(product)" }).toArray()` | `mongodb://localhost:27017/shopdocs?authSource=admin` | `mongosh` (`mongo:7`) |
  | Redis | one command per line, e.g. `HGETALL product:$(product)` | `redis://localhost:6379/0` | `redis-cli` (`redis:7-alpine`) |

  MongoDB's result (JSON) and Redis's replies (a list) are the card's `json` for the next cards, diamonds and links:
  `json.length > 0`, `json[0].stars >= 4`. The image field is the client's image; empty means the type's own.
- **Kafka cards**: read one topic, read only, from a Kafka config (bootstrap servers + consumer properties,
  in the Kafka tab of the side panel). Consume puts the topic's latest message (or, with "wait for next", the next
  one to arrive) in the card's box. Its JSON path takes a value out of it, like an HTTP card's, and its java gets
  the box as the `MESSAGE` env var (plus `TOPIC` and the variables so far); `set("name", value)` passes `$(name)`
  on to the next cards. The consumer is assigned the partitions itself: no consumer group, no commits, no
  topic creation, and `group.id` / `enable.auto.commit` / the deserializers can't be overridden.
- Cards are movable, resizable islands that can be linked into chains (run depth-first).
- They sit on a board you pan (drag an empty spot, Space + drag, wheel) and zoom (Ctrl + wheel, or the
  controls at the bottom; Fit shows every card). The board grows when a card gets near its edge.
- **If diamonds** check the previous card's result and follow their green (true) or red (false) port:
  a condition checked in Java (`status == 200 && data.state == "done"`) or Python returning `True`/`False`.
- **Sessions** (bottom left): save the page under a name, open a saved session or a copy of this one in a new tab.
  Each is a JSON file in `sessions/`, in three parts: `tabs` (the cards, each with its `id` and `kind`), `configs`
  (`http`, `db`, `kafka`; the HTTP ones as on screen, without their bearer tokens) and `links`
  (`{"from", "to", "when"?, "branch"?}`, `branch` being `true`/`false` out of an if diamond). Opening a session makes
  its configs the current ones. Sessions saved before this format still open.
- **Conditions on links**: click a link to give it a condition; it's only followed when that holds. An expression
  (`status == 200 && data.state == "done"`), or a **python** / **java** check (one line is the expression to return).
- **Variables flow down the path.** Each card adds to the variables the cards after it get: an HTTP or Kafka card's
  JSON path, and whatever a script `set(...)`s (query, Kafka, if diamond and link scripts). A branch keeps its own.
  `$(name)` fills them into an HTTP card's URL, body and header values (e.g. a token a login card set), a query and a
  Kafka topic. Every script, python or java, gets them in and passes them on the same way:

  | | python | java |
  |---|---|---|
  | the variables so far | `vars["id"]`, `var("id")`, env vars | `VARS.get("id")`, `var("id")`, `System.getenv("id")` |
  | the previous card's result | `prev["status"]`, `prev["json"]["state"]` | `num("status")`, `str("json.state")`, `has("json.id")`, `PREV` |
  | pass one on | `set("token", value)`, `set(token=value)` | `set("token", value)` |
  | a query card's executions | `run(name="x", price=1)` | `run("name", "x", "price", 1)` |
  | a Kafka card's message | `MESSAGE`, `TOPIC` | `MESSAGE`, `TOPIC` |
  | a diamond's / link's check | `return prev["status"] == 200` | `return num("status") == 200;` |

## Run

From the IDE or with Maven (needs Docker on the machine for the python / psql containers):

```bash
mvn spring-boot:run          # PORT defaults to 29993
```

Containerized, configs and the saved page live on the `client-data` volume:

```bash
docker compose up -d --build
```

Open http://localhost:29993/client, the same as when it runs from the IDE.

The image is `todokaima/client` on Docker Hub. Build and push it, or run it without compose:

```bash
docker build -t todokaima/client:latest . && docker push todokaima/client:latest
docker run -d --name client --network host -v client-data:/data -v /var/run/docker.sock:/var/run/docker.sock todokaima/client:latest
```

It runs on the host's network (see `compose.yaml`), so there's no `-p`: it listens on the host's 29993 itself.

| Env var    | Default            | Meaning                                      |
|------------|--------------------|----------------------------------------------|
| `PORT`     | `29993`            | HTTP port                                    |
| `DATA_DIR` | `.` (`/data` in the image) | Where configs and `workspace.json` are saved |
| `LOCALHOST_ALIAS` | (none) | In a container: what `localhost` in the HTTP cards, DB and Kafka configs and `CAPTURE_URL` means, e.g. `host.docker.internal` (this machine, as in the browser). The DB cards' psql then runs with that name instead of the host network. Unset: as written |
| `CAPTURE_URL` | `http://localhost:8000/api/capture` | Posted on every Send, before the chain runs: [scapy-minimal](../scapy-minimal) starts a capture, drawn in NetCanvas. `none`: don't. If it isn't running the chain runs anyway |

## Test database, Kafka and app

They live in [`../testclient`](../testclient): `docker compose up -d --build` there starts Postgres on
`localhost:2345`, Kafka on `localhost:9092` (topic `orders.events`) and an app with `POST /orders` on
`localhost:8085`, plus a chain to try. Remove the old standalone container first if you still have it:
`docker rm -f test-db`.

Default DB config: url `jdbc:postgresql://localhost:5432/irm_db`, schema `irm_uiconfiguration_db`, user/password `postgres`.
