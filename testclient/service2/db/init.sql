-- service2 (the kitchen). Runs once, automatically, when its postgres container starts fresh
CREATE SCHEMA kitchen;

-- How long each product takes; products without a row take 2 seconds. Prices live in service1's shop.products
CREATE TABLE kitchen.recipes (
    product      TEXT PRIMARY KEY,
    prep_seconds INT NOT NULL
);

INSERT INTO kitchen.recipes (product, prep_seconds) VALUES
    ('Coffee', 2),
    ('Tea',    1),
    ('Cake',   3);

-- One per service1 order, made through POST /tickets. state goes cooking -> ready (after prep_seconds) -> served
-- (when service1 says the order is done). processing_id is the service1 order's.
CREATE TABLE kitchen.tickets (
    id            SERIAL PRIMARY KEY,
    processing_id TEXT NOT NULL UNIQUE,
    product       TEXT NOT NULL,
    quantity      INT NOT NULL,
    state         TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at      TIMESTAMPTZ
);
