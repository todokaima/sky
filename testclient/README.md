# testclient

Things for the [client](../client) workbench to test against: two small Spring Boot services that talk to each
other over HTTP and Kafka, each with its own Postgres, and a single-node Kafka.

- **service1** is the shop: products and orders.
- **service2** is the kitchen: a ticket for every order, cooked for a few seconds, and the menu.

```bash
docker compose up -d --build
```

| What        | On the host      | Details                                                          |
|-------------|------------------|------------------------------------------------------------------|
| service1    | `localhost:8085` | the shop, debug endpoints under `/actuator`, OpenAPI at `/v3/api-docs` |
| service2    | `localhost:8086` | the kitchen, debug endpoints under `/actuator`, OpenAPI at `/v3/api-docs` |
| service1-db | `localhost:2345` | user `teo`, password `kaima`, database `testdb`, schema `shop`     |
| service2-db | `localhost:2346` | user `teo`, password `kaima`, database `testdb2`, schema `kitchen` |
| Kafka       | `localhost:9092` | plaintext, topics `orders.events` and `kitchen.events` (3 partitions each) |
| Debuggers   | `localhost:5005`, `localhost:5006` | JDWP for service1 and service2, for debugview's Debugger tab or the IDE's Remote JVM Debug |
| loyalty-db  | `localhost:3307` | MySQL 8.4, user `teo`, password `kaima`, database `loyalty`: `customers`, `points` |
| warehouse-db | `localhost:3308` | MariaDB 11, user `teo`, password `kaima`, database `warehouse`: `stock`, `deliveries` |
| docs-db     | `localhost:27017` | MongoDB 7, user `teo`, password `kaima` (authSource `admin`), database `shopdocs`: `reviews`, `audit` |
| cache       | `localhost:6379` | Redis 7, password `kaima`: `stock:<product>`, `product:<product>` hashes, `orders:recent` list, `tags:Coffee` set, `orders:count` |

The two services' ports are in `.env` (`SERVICE1_PORT=8085`, `SERVICE2_PORT=8086`): each service listens on its
port in its container, it's published on the host, and the other service calls it there. Change them there (then
`docker compose up -d` again), or for one run: `SERVICE1_PORT=9085 docker compose up -d`. The rest of this README
uses the defaults.

`/v3/api-docs` (springdoc) describes each service's endpoints; paste it in NetCanvas (`../graphViz`, *Capture
endpoints*) and [scapy-minimal](../scapy-minimal) draws the requests to them.

Each `*/db/init.sql` runs when its database starts fresh (`docker compose down` throws the data away), and so do
the other stores' seeds in `stores/` (`mysql/init.sql`, `mariadb/init.sql`, `mongo/init.js`, `redis/seed.redis`,
loaded by the one-shot `cache-seed`). The services don't use those stores: they're there for the client's DB configs
of other types (MySQL / MariaDB, MongoDB, Redis).

Coming from the old one-app stack (`test-app`, `test-db`)? Take it down first, the new containers use the same
ports: `docker compose down --remove-orphans` (or `docker rm -f test-app test-db`).

## Background traffic

So there's always something to sniff and draw, the services also call each other on their own (`Chatter.java` in
each), starting 20 s after they're up:

| Every | Who | What |
|---|---|---|
| 15 s | service2 | `GET` service1's `/products` (the kitchen checking the prices) |
| 20 s | service1 | `GET` service2's `/menu`, which service2 answers by calling service1's `GET /products` back |
| 45 s | service2 | a staff meal: `POST` service1's `/orders` with `"placedBy": "kitchen"` (Coffee, Tea, Cake in turn), so the ticket, the cooking and the Kafka events follow like any order |

`CHATTER_ENABLED=false docker compose up -d` turns it off, e.g. when a client chain waits for the *next* message on
`orders.events` and a staff meal's could come first. The intervals are `CHATTER_PRODUCTSEVERY`,
`CHATTER_MEALEVERY` (service2) and `CHATTER_MENUEVERY` (service1), as ISO durations: `PT30S`, `PT2M`.

## Who calls what

```
              HTTP POST /tickets, GET /tickets/{id}, PUT /tickets/{id}
           ─────────────────────────────────────────────────────────────▶
 service1                                                                  service2
 (shop)    ◀─────────────────────────────────────────────────────────────  (kitchen)
              HTTP POST /orders, GET /products, PUT /products/{name}

           ── orders.events  (created, updated, done) ──────────────────▶
           ◀───────────────────────────────── kitchen.events  (cooking, ready, served) ──
```

### service1, the shop (`localhost:8085`)

| Endpoint                         | What it does                                                                                     | Called by |
|----------------------------------|--------------------------------------------------------------------------------------------------|-----------|
| `POST /orders`                   | `{"product": "Coffee", "quantity": 2}` → stores the order, **POSTs service2's `/tickets`**, sends `created` to `orders.events` | you, service2's `/staff-meals` |
| `GET /orders/{processingId}`     | the order, with its `ticket` from **service2's `GET /tickets/{processingId}`**                   | you |
| `PUT /orders/{processingId}`     | `{"quantity": 3}` → **PUTs service2's `/tickets/{processingId}`**, then changes the order, sends `updated` | you |
| `GET /products`                  | the products with their prices                                                                   | you, service2's `/menu` |
| `PUT /products/{name}`           | `{"price": 3.80}`                                                                                | you, service2's `/menu/{product}` |

It reads `kitchen.events`: when a ticket is `ready` it marks the order `done` and sends `done` to `orders.events`.
If service2 can't take the ticket, the order is `failed` and `POST /orders` answers 502.

### service2, the kitchen (`localhost:8086`)

| Endpoint                         | What it does                                                                                     | Called by |
|----------------------------------|--------------------------------------------------------------------------------------------------|-----------|
| `POST /tickets`                  | `{"processingId", "product", "quantity"}` → stores the ticket as `cooking`, sends it to `kitchen.events`, and sends it again as `ready` after the product's prep time | service1's `POST /orders` |
| `GET /tickets/{processingId}`    | the ticket                                                                                       | service1's `GET /orders/{id}` |
| `PUT /tickets/{processingId}`    | `{"quantity": 3}`                                                                                | service1's `PUT /orders/{id}` |
| `GET /menu`                      | **service1's `GET /products`**, with each product's `prepSeconds`                                | you |
| `PUT /menu/{product}`            | `{"price": 3.80, "prepSeconds": 4}`, either or both → price **PUT to service1's `/products/{name}`**, prep time kept here | you |
| `POST /staff-meals`              | `{"product": "Cake"}` → **POSTs service1's `/orders`** with `"placedBy": "kitchen"` (which then POSTs the ticket back) | you |

It reads `orders.events`: when an order is `done` it marks the ticket `served` and sends it to `kitchen.events`.
Prep times are Coffee 2s, Tea 1s, Cake 3s (2s for anything without a recipe).

## The whole round trip

```bash
curl -s localhost:8085/orders -H 'Content-Type: application/json' -d '{"product": "Coffee", "quantity": 2}'
# {"processingId":"5b0c…","orderId":1,"ticketId":1,"product":"Coffee","quantity":2,"placedBy":"customer","state":"created"}
```

1. service1 stores the order (`created`) and POSTs the ticket to service2, which stores it (`cooking`) and sends
   it to `kitchen.events`. service1 sends `created` to `orders.events`.
2. Two seconds later service2 marks the ticket `ready` and sends it to `kitchen.events`.
3. service1 reads that, marks the order `done` and sends `done` to `orders.events`.
4. service2 reads that, marks the ticket `served` and sends it to `kitchen.events`.

```bash
curl -s localhost:8085/orders/5b0c…                       # the order and its ticket
curl -s -X PUT localhost:8085/orders/5b0c… -H 'Content-Type: application/json' -d '{"quantity": 3}'
curl -s localhost:8086/menu
curl -s -X PUT localhost:8086/menu/Cake -H 'Content-Type: application/json' -d '{"price": 5.20, "prepSeconds": 5}'
curl -s localhost:8086/staff-meals -H 'Content-Type: application/json' -d '{"product": "Tea"}'
```

All messages are JSON keyed by the processingId. On `orders.events`:
`{"event": "done", "processingId", "orderId", "ticketId", "product", "quantity", "placedBy", "state": "done"}`;
on `kitchen.events`: `{"ticketId", "processingId", "product", "quantity", "state": "ready"}`.

## A chain to try in the client

The client's default DB config (`localhost:2345/testdb`, schema `shop`) and Kafka config (`localhost:9092`) point
at service1's side. For service2's database use `localhost:2346/testdb2`, schema `kitchen`.

1. **HTTP card**: `POST http://localhost:8085/orders`, body `{"product": "Coffee", "quantity": 2}`,
   JSON path `processingId`.
2. **Kafka card**, linked after it: topic `orders.events`, **wait for next**, timeout `10`, JSON path `state`.
   It gets the `done` message about two seconds later. Its java can check it's the same order:
   ```java
   set("sameOrder", MESSAGE.contains(System.getenv("processingId")));
   ```
3. **Query card**, linked after that: `SELECT * FROM orders WHERE processing_id = '$(processingId)';`
4. **Query card** on service2's database: `SELECT * FROM tickets WHERE processing_id = '$(processingId)';`
   (`served` by now).
5. **HTTP card**: `GET http://localhost:8085/orders/$(processingId)`, JSON path `ticket.state`.

Or wait on `kitchen.events` instead, for `ready` then `served`.

Watch a topic from a terminal:

```bash
docker exec -it test-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic kitchen.events --from-beginning --property print.key=true
```

Send a message by hand, e.g. pretend the kitchen finished an order (service1 then marks it done):

```bash
echo '{"processingId": "5b0c…", "state": "ready"}' | docker exec -i test-kafka \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic kitchen.events
```

Run the services from the IDE instead: `docker compose up -d service1-db service2-db kafka`, then
`mvn spring-boot:run` in `service1/` and in `service2/`. Their defaults are the host ports above (service1 on 8085,
service2 on 8086), so they find each other. Run both that way: one left in docker calls the other by its compose
name and can't reach the IDE one.
