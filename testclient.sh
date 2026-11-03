#!/usr/bin/env bash
# Starts the testclient services (testclient/: service1 :8085, service2 :8086, their Postgres :2345 / :2346,
# Kafka :9092), waits until they answer, pastes their /v3/api-docs in NetCanvas (when it's up, see run.sh) and writes
# two sessions for the client with their configs: "testclient orders flow" (the order's round trip) and "testclient
# tour" (one of everything: both services, Kafka, the six stores, python / java, every kind of check) and "testclient
# advanced" (a few of everything, in five lanes, the values building up all the way). Open them from
# the client's Sessions menu.
#
#   ./testclient.sh             start them (building the images the first time), then the above
#   ./testclient.sh --force     the same, writing the sessions again over saved ones of those names
#   ./testclient.sh --session   only write the session (services already up, or started some other way)
#   ./testclient.sh --down      stop them (docker compose down: their databases start fresh next time)

set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
TESTCLIENT="$(cd "$ROOT/testclient" 2>/dev/null && pwd)" || { echo "No testclient/ in $ROOT"; exit 1; }
SESSIONS="$ROOT/client/sessions"
SESSION_NAME="testclient orders flow"
FORCE=""
UP=1
while [ $# -gt 0 ]; do
    case "$1" in
        --force) FORCE=1 ;;
        --session) UP="" ;;
        --down) (cd "$ROOT" && docker compose down); exit $? ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        *) echo "Unknown option $1 (--force, --session, --down)"; exit 1 ;;
    esac
    shift
done

if [ -n "$UP" ]; then
    command -v docker >/dev/null || { echo "docker not found"; exit 1; }
    echo "Starting the testclient services (the first time builds their images, a few minutes)…"
    (cd "$ROOT" && docker compose up -d --build) || exit 1
fi

# Waits for the services, pastes their api-docs in NetCanvas, writes the session: python, so no curl is needed
python3 -I - "$SESSIONS" "$SESSION_NAME" "$FORCE" "$UP" <<'EOF'
import json, os, sys, time, urllib.request

sessions, name, force, up = sys.argv[1], sys.argv[2], sys.argv[3] == "1", sys.argv[4] == "1"
SERVICES = {8085: "service1 (shop)", 8086: "service2 (kitchen)"}

def get(url, timeout=3):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read()

if up:
    for port, label in SERVICES.items():
        deadline = time.time() + 240
        while True:
            try:
                get(f"http://localhost:{port}/v3/api-docs")
                print(f"  {label} is up on :{port}")
                break
            except Exception:
                if time.time() > deadline:
                    sys.exit(f"  {label} didn't answer on :{port} in 4 minutes: docker compose logs")
                time.sleep(2)

# NetCanvas draws the requests to the endpoints pasted there; run.sh may not be running, that's fine
try:
    get("http://localhost:29994/api/config", 2)
    for port in SERVICES:
        try:
            docs = get(f"http://localhost:{port}/v3/api-docs")
            request = urllib.request.Request(f"http://localhost:29994/api/apidocs/{port}", data=docs, method="PUT",
                                             headers={"Content-Type": "application/json"})
            urllib.request.urlopen(request, timeout=5).read()
            print(f"  pasted :{port}'s api-docs in NetCanvas")
        except Exception as error:
            print(f"  :{port}'s api-docs not pasted: {error}")
except Exception:
    print("  NetCanvas isn't running (./run.sh starts it): api-docs not pasted")

# ---- The session: tabs (the cards), configs, links, as the client saves them ----
def http(method, url, body="", extractor="", layout="", config=-1):
    return {"method": method, "url": url, "body": body, "output": "", "extractor": extractor, "extractorOutput": "",
            "responseHidden": False, "layout": layout, "config": config}

def query(sql, script="", language="python", layout="", config=-1):
    return {"language": language, "vars": script, "query": sql, "schema": "", "output": "",
            "pyHidden": not script, "layout": layout, "config": config}

tabs = [
    {"id": "h0", "kind": "http", **http("POST", "http://localhost:8085/orders", '{"product": "Coffee", "quantity": 2}',
                                        "processingId", "200,200,480,")},
    {"id": "h1", "kind": "http", **http("GET", "$(orderUrl)", "", "ticket.state", "2620,220,480,")},
    {"id": "q0", "kind": "query", **query(
        "SELECT o.id, o.processing_id, p.name AS product, o.quantity, o.placed_by, o.state, o.ticket_id\n"
        "FROM orders o JOIN products p ON p.id = o.product_id\n"
        "WHERE o.processing_id = '$(processingId)';",
        "# The variables so far come in as vars (and as env vars); set(...) passes new ones on to the next cards\n"
        "set(\"orderUrl\", \"http://localhost:8085/orders/\" + vars[\"processingId\"])\n"
        "print(\"order\", vars[\"processingId\"], \"is\", vars[\"state\"])",
        layout="1980,120,540,", config=0)},
    {"id": "q1", "kind": "query", **query(
        "SELECT id, processing_id, product, quantity, state, created_at, ready_at\n"
        "FROM tickets WHERE processing_id = '$(processingId)';",
        layout="1980,620,540,", config=1)},
    {"id": "q2", "kind": "query", **query(
        "SELECT c.name, c.email, c.tier, p.points\n"
        "FROM points p JOIN customers c ON c.id = p.customer_id\n"
        "WHERE p.product = '$(product)' ORDER BY p.points DESC;",
        layout="3220,0,560,", config=2)},
    {"id": "q3", "kind": "query", **query(
        "SELECT product, on_hand, reorder_at, on_hand <= reorder_at AS reorder_now, supplier\n"
        "FROM stock WHERE product = '$(product)';",
        layout="3220,420,560,", config=3)},
    {"id": "q4", "kind": "query", **query(
        "db.reviews.find({ product: \"$(product)\" }, { _id: 0 }).sort({ stars: -1 }).toArray()",
        layout="3220,840,560,", config=4)},
    {"id": "q5", "kind": "query", **query(
        "# One Redis command per line\n"
        "HGETALL product:$(product)\n"
        "GET stock:$(product)\n"
        "INCR orders:count\n"
        "RPUSH orders:recent $(processingId)\n"
        "LRANGE orders:recent -3 -1",
        "# Before the commands: how many reviews MongoDB found, from the card before (prev = If 2's input)\n"
        "set(\"reviews\", len(prev[\"json\"]) if prev and isinstance(prev.get(\"json\"), list) else 0)",
        layout="4400,840,560,", config=5)},
    {"id": "d1", "kind": "if", "mode": "condition", "output": "", "layout": "3900,900,380,",
     "condition": "json.length > 0"},
    {"id": "d0", "kind": "if", "mode": "java", "output": "", "layout": "1500,240,380,",
     "condition": "str(\"json.state\").equals(\"done\") && \"true\".equals(var(\"sameOrder\"))"},
    {"id": "k0", "kind": "kafka", "topic": "orders.events", "mode": "next", "timeout": 10, "meta": "", "output": "",
     "extractor": "state", "extractorOutput": "", "scriptLanguage": "java", "scriptHidden": False, "scriptOutput": "",
     "script": "// The next message on orders.events: the \"done\" of the order just placed, about 2 s later\n"
               "boolean same = MESSAGE.contains(var(\"processingId\"));\n"
               "System.out.println(same ? \"this order's message\" : \"another order's message\");\n"
               "set(\"sameOrder\", same);\n"
               "// The product, for the stores after HTTP 2 (MySQL, MariaDB, MongoDB, Redis)\n"
               "var product = java.util.regex.Pattern.compile(\"\\\"product\\\"\\\\s*:\\\\s*\\\"([^\\\"]+)\\\"\").matcher(MESSAGE);\n"
               "if (product.find()) set(\"product\", product.group(1));",
     "layout": "800,200,600,", "config": -1},
]

configs = {
    "http": [{"accept": "application/json", "contentType": "application/json", "jwt": "", "headers": "",
              "defaultConfig": True}],
    "db": [
        {"type": "postgres", "containerName": "db-script-runner", "image": "postgres:alpine", "pythonImage": "python:3-alpine",
         "javaImage": "eclipse-temurin:21-jdk-alpine", "url": "jdbc:postgresql://localhost:2345/testdb", "schema": "shop",
         "username": "teo", "password": "kaima", "driver": "org.postgresql.Driver", "defaultConfig": True},
        {"type": "postgres", "containerName": "db-script-runner", "image": "postgres:alpine", "pythonImage": "python:3-alpine",
         "javaImage": "eclipse-temurin:21-jdk-alpine", "url": "jdbc:postgresql://localhost:2346/testdb2", "schema": "kitchen",
         "username": "teo", "password": "kaima", "driver": "org.postgresql.Driver", "defaultConfig": False},
        {"type": "mysql", "containerName": "db-script-runner", "image": "mysql:8.4", "pythonImage": "python:3-alpine",
         "javaImage": "eclipse-temurin:21-jdk-alpine", "url": "jdbc:mysql://localhost:3307/loyalty", "schema": "",
         "username": "teo", "password": "kaima", "driver": "com.mysql.cj.jdbc.Driver", "defaultConfig": False},
        {"type": "mysql", "containerName": "db-script-runner", "image": "mysql:8.4", "pythonImage": "python:3-alpine",
         "javaImage": "eclipse-temurin:21-jdk-alpine", "url": "jdbc:mariadb://localhost:3308/warehouse", "schema": "",
         "username": "teo", "password": "kaima", "driver": "org.mariadb.jdbc.Driver", "defaultConfig": False},
        {"type": "mongodb", "containerName": "db-script-runner", "image": "mongo:7", "pythonImage": "python:3-alpine",
         "javaImage": "eclipse-temurin:21-jdk-alpine", "url": "mongodb://localhost:27017/shopdocs?authSource=admin", "schema": "",
         "username": "teo", "password": "kaima", "driver": "", "defaultConfig": False},
        {"type": "redis", "containerName": "db-script-runner", "image": "redis:7-alpine", "pythonImage": "python:3-alpine",
         "javaImage": "eclipse-temurin:21-jdk-alpine", "url": "redis://localhost:6379/0", "schema": "",
         "username": "", "password": "kaima", "driver": "", "defaultConfig": False},
    ],
    "kafka": [{"bootstrapServers": "localhost:9092", "properties": "security.protocol=PLAINTEXT",
               "containerName": "kafka-script-runner", "javaImage": "eclipse-temurin:21-jdk-alpine",
               "pythonImage": "python:3-alpine", "defaultConfig": True}],
}

links = [
    {"from": "h0", "to": "k0", "when": "ok"},
    {"from": "k0", "to": "d0"},
    {"from": "d0", "to": "q0", "branch": "true"},
    {"from": "d0", "to": "q1", "branch": "true"},
    {"from": "q0", "to": "h1"},
    {"from": "h1", "to": "q2"},
    {"from": "h1", "to": "q3"},
    {"from": "h1", "to": "q4"},
    {"from": "q4", "to": "d1"},
    {"from": "d1", "to": "q5", "branch": "true"},
]

os.makedirs(sessions, exist_ok=True)
path = os.path.join(sessions, name + ".json")
if os.path.exists(path) and not force:
    print(f"  session \"{name}\" is there already (--force writes it again): {path}")
else:
    with open(path, "w") as file:
        json.dump({"tabs": tabs, "configs": configs, "links": links}, file, indent=2)
    print(f"  wrote the session \"{name}\": {path}")

# ---- "testclient tour": one of everything (both services, Kafka, the six stores, python and java scripts,
# diamonds in all three modes, link conditions in all three languages), same configs ----
def kafka(topic, mode, extractor, script, language, layout):
    return {"topic": topic, "mode": mode, "timeout": 10, "meta": "", "output": "", "extractor": extractor,
            "extractorOutput": "", "script": script, "scriptLanguage": language, "scriptHidden": False,
            "scriptOutput": "", "layout": layout, "config": -1}

def decision(mode, condition, layout):
    return {"mode": mode, "condition": condition, "output": "", "layout": layout}

tour = [
    # HTTP: service2's menu, which service2 builds by calling service1's /products
    {"id": "h0", "kind": "http", **http("GET", "http://localhost:8086/menu", "", "[0].name", "200,200,460,")},
    # PostgreSQL (service1), with a python script that renames the menu's first product for the cards after it
    {"id": "q0", "kind": "query", **query(
        "SELECT name, price FROM products WHERE name = '$(product)';",
        "# python: vars has HTTP 1's extracted name; set(...) passes product on to every card after this one\n"
        "set(\"product\", vars[\"name\"])\n"
        "print(\"menu starts with\", vars[\"name\"])",
        layout="760,200,520,", config=0)},
    # HTTP: an order for that product (service1 POSTs the ticket to service2 and sends orders.events)
    {"id": "h1", "kind": "http", **http("POST", "http://localhost:8085/orders",
                                        '{"product": "$(product)", "quantity": 1}', "processingId", "1380,200,460,")},
    # Kafka: the newest message on kitchen.events, read with a python script
    {"id": "k0", "kind": "kafka", **kafka("kitchen.events", "latest", "state",
        "# python: MESSAGE is the message, vars the variables so far\n"
        "import json\n"
        "message = json.loads(MESSAGE)\n"
        "set(\"ticketId\", message.get(\"ticketId\"))\n"
        "set(\"ticketForThisOrder\", message.get(\"processingId\") == vars[\"processingId\"])",
        "python", "1940,200,580,")},
    # If (python): passes a value on down the branch it takes
    {"id": "d0", "kind": "if", **decision("python",
        "# python: prev is Kafka 1's result, vars the variables so far\n"
        "set(\"kitchenSaw\", prev[\"json\"].get(\"state\"))\n"
        "return prev[\"ok\"]", "2620,240,380,")},
    # PostgreSQL (service2): the ticket
    {"id": "q1", "kind": "query", **query(
        "SELECT id, processing_id, product, state FROM tickets WHERE processing_id = '$(processingId)';",
        layout="3100,0,520,", config=1)},
    # MySQL: who earned points on the product
    {"id": "q2", "kind": "query", **query(
        "SELECT c.name, c.tier, SUM(p.points) AS points FROM points p JOIN customers c ON c.id = p.customer_id\n"
        "WHERE p.product = '$(product)' GROUP BY c.name, c.tier ORDER BY points DESC;",
        layout="3100,420,560,", config=2)},
    # MariaDB, with a java script: two runs of the query, one per product
    {"id": "q3", "kind": "query", **query(
        "SELECT product, on_hand, reorder_at, on_hand <= reorder_at AS reorder_now FROM stock WHERE product = '$(which)';",
        "// java: each run(...) is one execution of the query; var(\"x\") reads the variables so far\n"
        "run(\"which\", var(\"product\"));\n"
        "run(\"which\", \"Cake\");\n"
        "set(\"checkedStock\", 2);",
        language="java", layout="3760,420,560,", config=3)},
    # If (java): the MariaDB card's exit code
    {"id": "d1", "kind": "if", **decision("java", "num(\"exit\") == 0", "4420,460,380,")},
    # MongoDB: the product's reviews, as JSON for the next cards
    {"id": "q4", "kind": "query", **query(
        "db.reviews.find({ product: \"$(product)\" }, { _id: 0 }).sort({ stars: -1 }).toArray()",
        layout="4900,420,560,", config=4)},
    # If (expression): any reviews?
    {"id": "d2", "kind": "if", **decision("condition", "json.length > 0 && json[0].stars >= 3", "5560,460,380,")},
    # Redis (true): the product's hash and stock, and a counter
    {"id": "q5", "kind": "query", **query(
        "HGETALL product:$(product)\nGET stock:$(product)\nINCR tour:runs\nSET tour:last $(processingId)",
        layout="6040,300,520,", config=5)},
    # HTTP (false): the order, read back
    {"id": "h2", "kind": "http", **http("GET", "http://localhost:8085/orders/$(processingId)", "", "state", "6040,760,460,")},
]

tourLinks = [
    {"from": "h0", "to": "q0", "when": "python: len(prev[\"json\"]) > 0"},
    {"from": "q0", "to": "h1", "when": "java: num(\"exit\") == 0 && var(\"product\") != null"},
    {"from": "h1", "to": "k0", "when": "status == 200 || status == 201"},
    {"from": "k0", "to": "d0"},
    {"from": "d0", "to": "q1", "branch": "true"},
    {"from": "d0", "to": "q2", "branch": "true"},
    {"from": "q2", "to": "q3"},
    {"from": "q3", "to": "d1"},
    {"from": "d1", "to": "q4", "branch": "true"},
    {"from": "q4", "to": "d2"},
    {"from": "d2", "to": "q5", "branch": "true"},
    {"from": "d2", "to": "h2", "branch": "false"},
]

tourPath = os.path.join(sessions, "testclient tour.json")
if os.path.exists(tourPath) and not force:
    print(f"  session \"testclient tour\" is there already (--force writes it again): {tourPath}")
else:
    with open(tourPath, "w") as file:
        json.dump({"tabs": tour, "configs": configs, "links": tourLinks}, file, indent=2)
    print(f"  wrote the session \"testclient tour\": {tourPath}")

# ---- "testclient advanced": a few of everything, in lanes, with the values building up all the way along ----
#   lane 1  HTTP: the menu -> the cheapest product (python) -> order it -> change it -> its ticket
#   lane 2  Kafka: this order's events (python, java), checked by diamonds of every kind
#   lane 3  SQL: service1's and service2's PostgreSQL, MySQL loyalty (java runs per tier), MariaDB stock
#   lane 4  NoSQL: MongoDB reviews (aggregate) -> rating into Redis; an audit document written and read back
#   lane 5  Redis: stock of every product (python runs), counters, and the order read back over HTTP
def at(column, row, width=520):
    return f"{200 + column * 640},{120 + row * 470},{width},"

adv = [
    # lane 1: HTTP
    {"id": "h0", "kind": "http", **http("GET", "http://localhost:8086/menu", "", "", at(0, 0, 460))},
    {"id": "q0", "kind": "query", **query(
        "SELECT name, price FROM products WHERE name = '$(product)';",
        "# python: prev is HTTP 1's result; pick the cheapest product on the menu and pass it on\n"
        "menu = prev[\"json\"]\n"
        "cheapest = min(menu, key=lambda item: float(item[\"price\"]))\n"
        "set(\"product\", cheapest[\"name\"])\n"
        "set(\"price\", cheapest[\"price\"])\n"
        "set(\"menuSize\", len(menu))\n"
        "print(\"cheapest:\", cheapest)",
        layout=at(1, 0), config=0)},
    {"id": "h1", "kind": "http", **http("POST", "http://localhost:8085/orders",
                                        '{"product": "$(product)", "quantity": 2}', "processingId", at(2, 0, 460))},
    {"id": "h2", "kind": "http", **http("PUT", "http://localhost:8085/orders/$(processingId)",
                                        '{"quantity": 3}', "", at(3, 0, 460))},
    {"id": "h3", "kind": "http", **http("GET", "http://localhost:8086/tickets/$(processingId)", "", "state", at(4, 0, 460))},
    {"id": "d0", "kind": "if", **decision("condition", 'state == "cooking" || state == "ready" || state == "served"', at(5, 0, 380))},
    {"id": "q1", "kind": "query", **query(
        "SELECT id, processing_id, product, quantity, state, created_at, ready_at\n"
        "FROM tickets WHERE processing_id = '$(processingId)';", layout=at(6, 0), config=1)},
    {"id": "h4", "kind": "http", **http("GET", "http://localhost:8085/orders/$(processingId)", "", "ticket.state", at(6, 1, 460))},

    # lane 2: Kafka
    {"id": "k0", "kind": "kafka", **kafka("orders.events", "latest", "event",
        "# python: the newest message on orders.events, after HTTP 3's PUT: is it ours?\n"
        "import json\n"
        "message = json.loads(MESSAGE)\n"
        "set(\"ours\", message.get(\"processingId\") == vars[\"processingId\"])\n"
        "set(\"eventQuantity\", message.get(\"quantity\"))",
        "python", at(1, 2, 580))},
    {"id": "d1", "kind": "if", **decision("java",
        "// java: our order's message, with the quantity HTTP 3 set\n"
        "return \"true\".equals(var(\"ours\")) && num(\"json.quantity\") == 3;", at(2, 2, 380))},
    {"id": "k1", "kind": "kafka", **kafka("kitchen.events", "latest", "state",
        "// java: the kitchen's newest event; ready or served means it cooked our order\n"
        "boolean ours = MESSAGE.contains(var(\"processingId\"));\n"
        "set(\"kitchenOurs\", ours);\n"
        "System.out.println(ours ? \"the kitchen is on our order\" : \"another order's event\");",
        "java", at(3, 2, 580))},
    {"id": "d2", "kind": "if", **decision("python",
        "# python: the kitchen's event, for our order or not, tells what it's doing\n"
        "set(\"kitchenState\", prev[\"json\"].get(\"state\"))\n"
        "return prev[\"json\"].get(\"state\") in (\"cooking\", \"ready\", \"served\")", at(4, 2, 380))},
    {"id": "k2", "kind": "kafka", **kafka("orders.events", "latest", "state", "", "java", at(3, 3, 520))},

    # lane 3: SQL stores
    {"id": "q2", "kind": "query", **query(
        "SELECT name, email FROM customers WHERE tier = '$(tier)' ORDER BY name;",
        "// java: one run of the query per tier\n"
        "for (String tier : List.of(\"gold\", \"silver\")) {\n"
        "    run(\"tier\", tier);\n"
        "}\n"
        "set(\"tiersChecked\", \"gold,silver\");",
        language="java", layout=at(3, 4), config=2)},
    {"id": "q3", "kind": "query", **query(
        "SELECT c.name, c.tier, SUM(p.points) AS points FROM points p JOIN customers c ON c.id = p.customer_id\n"
        "WHERE p.product = '$(product)' GROUP BY c.name, c.tier ORDER BY points DESC;",
        layout=at(4, 4), config=2)},
    {"id": "q4", "kind": "query", **query(
        "SELECT product, on_hand, reorder_at, on_hand <= reorder_at AS reorder_now, supplier FROM stock ORDER BY on_hand;",
        layout=at(5, 4), config=3)},
    {"id": "q5", "kind": "query", **query(
        "SELECT product, quantity, arrived_at FROM deliveries WHERE product = '$(product)' ORDER BY arrived_at DESC;",
        "# python: the product's deliveries, and the price HTTP 1 found, for the cards after\n"
        "set(\"priceSeen\", vars.get(\"price\", \"?\"))",
        layout=at(6, 4), config=3)},
    {"id": "q6", "kind": "query", **query(
        "SELECT name, price FROM products ORDER BY price DESC;", layout=at(7, 4), config=0)},

    # lane 4: NoSQL
    {"id": "q7", "kind": "query", **query(
        "db.reviews.aggregate([\n"
        "  { $match: { product: \"$(product)\" } },\n"
        "  { $group: { _id: \"$product\", avg: { $avg: \"$stars\" }, reviews: { $sum: 1 } } }\n"
        "]).toArray()", layout=at(3, 5), config=4)},
    {"id": "d3", "kind": "if", **decision("condition", "json.length > 0 && json[0].avg >= 4", at(4, 5, 380))},
    {"id": "q8", "kind": "query", **query(
        "SET rating:$(product) high\nEXPIRE rating:$(product) 3600\nGET rating:$(product)", layout=at(5, 5), config=5)},
    {"id": "q9", "kind": "query", **query(
        "SET rating:$(product) low\nGET rating:$(product)", layout=at(5, 6), config=5)},
    {"id": "q10", "kind": "query", **query(
        "db.audit.insertOne({ at: new Date(), action: \"advanced.order\", processingId: \"$(processingId)\",\n"
        "                     product: \"$(product)\", quantity: 3, kitchen: \"$(kitchenState)\" })",
        layout=at(6, 5), config=4)},
    {"id": "q11", "kind": "query", **query(
        "db.audit.find({ processingId: \"$(processingId)\" }, { _id: 0 }).toArray()", layout=at(7, 5), config=4)},
    {"id": "d4", "kind": "if", **decision("java", "has(\"json.0.processingId\")", at(8, 5, 380))},

    # lane 5: Redis and the end
    {"id": "q12", "kind": "query", **query(
        "GET stock:$(key)\nHGET product:$(key) price",
        "# python: one run per product, each its own GET / HGET (Redis labels them with ECHO)\n"
        "for name in [\"Coffee\", \"Tea\", \"Cake\"]:\n"
        "    run(key=name)",
        layout=at(9, 5), config=5)},
    {"id": "q13", "kind": "query", **query(
        "INCR advanced:runs\nRPUSH orders:recent $(processingId)\nLRANGE orders:recent -5 -1\nSMEMBERS tags:Coffee\nTTL feature:discounts",
        layout=at(10, 5), config=5)},
    {"id": "h5", "kind": "http", **http("GET", "http://localhost:8085/orders/$(processingId)", "", "state", at(11, 5, 460))},
    {"id": "h6", "kind": "http", **http("GET", "http://localhost:8086/menu", "", "[0].prepSeconds", at(11, 6, 460))},
]

advLinks = [
    # lane 1
    {"from": "h0", "to": "q0", "when": "python: len(prev[\"json\"]) >= 1"},
    {"from": "q0", "to": "h1", "when": "java: num(\"exit\") == 0 && var(\"product\") != null"},
    {"from": "h1", "to": "h2", "when": "status == 200 || status == 201"},
    # lane 2 first (the newest events are this order's right after the PUT), then the rest of lane 1
    {"from": "h2", "to": "k0"},
    {"from": "h2", "to": "h3", "when": "ok"},
    {"from": "h3", "to": "d0"},
    {"from": "d0", "to": "q1", "branch": "true"},
    {"from": "d0", "to": "h4", "branch": "false"},
    {"from": "k0", "to": "d1"},
    {"from": "d1", "to": "k1", "branch": "true"},
    {"from": "d1", "to": "k2", "branch": "false"},
    {"from": "k1", "to": "d2"},
    # lane 3 (after the kitchen's check)
    {"from": "d2", "to": "q2", "branch": "true"},
    {"from": "d2", "to": "q3", "branch": "true"},
    {"from": "q3", "to": "q4", "when": "exit == 0"},
    {"from": "q4", "to": "q5"},
    {"from": "q5", "to": "q6", "when": "python: vars.get(\"priceSeen\") not in (None, \"?\")"},
    # lane 4
    {"from": "d2", "to": "q7", "branch": "false"},
    {"from": "d2", "to": "q7", "branch": "true"},
    {"from": "q7", "to": "d3"},
    {"from": "d3", "to": "q8", "branch": "true"},
    {"from": "d3", "to": "q9", "branch": "false"},
    {"from": "q8", "to": "q10"},
    {"from": "q9", "to": "q10"},
    {"from": "q10", "to": "q11", "when": "json.acknowledged == true"},
    {"from": "q11", "to": "d4"},
    # lane 5
    {"from": "d4", "to": "q12", "branch": "true"},
    {"from": "q12", "to": "q13"},
    {"from": "q13", "to": "h5", "when": "java: var(\"processingId\") != null"},
    {"from": "d4", "to": "h6", "branch": "false"},
]

advPath = os.path.join(sessions, "testclient advanced.json")
if os.path.exists(advPath) and not force:
    print(f"  session \"testclient advanced\" is there already (--force writes it again): {advPath}")
else:
    with open(advPath, "w") as file:
        json.dump({"tabs": adv, "configs": configs, "links": advLinks}, file, indent=2)
    print(f"  wrote the session \"testclient advanced\": {advPath}")
EOF
status=$?
[ $status -ne 0 ] && exit $status

cat <<TEXT

  In the client (http://localhost:29993/client): Sessions (bottom left) > "$SESSION_NAME" opens it in a new tab,
  with its configs (HTTP; service1's, service2's, MySQL, MariaDB, MongoDB, Redis; Kafka). Then Send on HTTP 1:

    HTTP 1   POST :8085/orders                     -> processingId
    Kafka 1  next message on orders.events (java)  -> state, sameOrder
    If 1     java: state is done and it's this order
    Query 1  service1's orders (python sets orderUrl)    Query 2  service2's tickets
    HTTP 2   GET \$(orderUrl)                       -> ticket.state
    then, for the order's product:
    Query 3  MySQL loyalty: who earned points on it      Query 4  MariaDB warehouse: its stock
    Query 5  MongoDB: its reviews (JSON) -> If 2 (any?) -> Query 6  Redis: its hash, stock, counters

TEXT
