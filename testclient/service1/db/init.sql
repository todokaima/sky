-- service1 (the shop). Runs once, automatically, when its postgres container starts fresh
CREATE SCHEMA shop;

CREATE TABLE shop.products (
    id    SERIAL PRIMARY KEY,
    name  TEXT NOT NULL
);

ALTER TABLE shop.products ADD COLUMN price NUMERIC(10, 2);

INSERT INTO shop.products (name, price) VALUES
    ('Coffee', 3.50),
    ('Tea',    2.20),
    ('Cake',   4.80);

-- Orders made through POST /orders (by a customer, or by service2 for a staff meal).
-- state goes created -> done when service2's kitchen says the ticket is ready, or failed if it couldn't take it.
-- ticket_id is service2's kitchen.tickets id.
CREATE TABLE shop.orders (
    id            SERIAL PRIMARY KEY,
    processing_id TEXT NOT NULL UNIQUE,
    product_id    INT NOT NULL REFERENCES shop.products (id),
    quantity      INT NOT NULL,
    placed_by     TEXT NOT NULL DEFAULT 'customer',
    state         TEXT NOT NULL,
    ticket_id     INT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
