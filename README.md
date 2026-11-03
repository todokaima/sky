# sky

A workbench for testing services that talk over HTTP, Kafka and databases, and for watching that traffic:

| App | What it is | Where |
|---|---|---|
| **client** | cards (HTTP, PostgreSQL, MySQL, MariaDB, MongoDB, Redis, Kafka, if diamonds) linked into chains; python / java scripts pass values down the path | http://localhost:29993/client |
| **netgraph** | NetCanvas: the graph of who called whom, with each request and response on its wire | http://localhost:29994 |
| **scapy-minimal** | every HTTP request and response on this machine, plus the stores' commands, with a filter and Edit & resend | http://localhost:29922/scapyminimal |
| **scapy-netgraph** | headless: sniffs the Docker bridges and draws what the client's actions cause in NetCanvas | `logs/scapy-netgraph.log` |
| **testclient** | what to test against: two services (shop :8085, kitchen :8086) with PostgreSQL, Kafka, MySQL, MariaDB, MongoDB, Redis | started by `docker compose` |

## Run it

Needs Linux (the sniffers read the Docker bridges), sudo, Docker with Compose 2.20+, Java 21 and Maven, Python 3 with
venv. On Debian / Ubuntu, `tools/install-deps.sh` installs whichever of them are missing (and adds you to the
`docker` group) and writes down what it added; `tools/uninstall-deps.sh` takes exactly that away again later:

```bash
tools/install-deps.sh            # only what's missing; then log out and in if it added you to the docker group
```

| Installed | For | Undone by `tools/uninstall-deps.sh` |
|---|---|---|
| `openjdk-21-jdk`, `maven` | the client and NetCanvas (run with Maven) | the packages, if it installed them |
| `python3-venv` | the sniffers' venv with scapy | the package, and `.venv` |
| `docker.io`, `docker-compose-v2` (only without Docker) | the test stack, the client's query and script containers | the packages, the docker group |
| `curl`, `git` | `run.sh`'s checks, cloning | the packages, if it installed them |
| (made by running) Maven's libraries in `~/.m2`, the images in Docker, `logs/` | | `--maven-cache`, `--docker-data`, `logs/` always |

```bash
docker compose up -d --build     # the test stack; the first time builds the services and pulls the images (a few GB)
./run.sh                         # the apps; asks for sudo once, Ctrl+C stops them
```

The first `./run.sh` makes a python venv with scapy (`.venv`) and three sample sessions for the test stack. In the
client, **Sessions** (bottom left) opens them, with their configs:

- **testclient orders flow**: an order's round trip, HTTP → Kafka → if → PostgreSQL → HTTP, then the other stores
- **testclient tour**: one of everything
- **testclient advanced**: a few of everything, in five lanes, the values building up all the way

Press **Send** on its first HTTP card. NetCanvas draws what that caused; scapy-minimal lists it.

## More

- `./testclient.sh --help`: start the stack and wait for it, write the sessions again (`--force`), stop it (`--down`)
- `CHATTER_ENABLED=false docker compose up -d`: the services stop calling each other in the background
- Each folder's README: the client's cards and scripts, NetCanvas's API, the sniffers' options, the test stack
