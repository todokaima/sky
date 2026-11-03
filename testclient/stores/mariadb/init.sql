-- warehouse (MariaDB, localhost:3308, teo / kaima): stock per product and what came in
CREATE TABLE stock (
    product   VARCHAR(40) PRIMARY KEY,
    on_hand   INT NOT NULL,
    reorder_at INT NOT NULL,
    supplier  VARCHAR(80) NOT NULL
);

CREATE TABLE deliveries (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    product     VARCHAR(40) NOT NULL,
    quantity    INT NOT NULL,
    arrived_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO stock (product, on_hand, reorder_at, supplier) VALUES
    ('Coffee', 120, 40, 'Bean & Co'), ('Tea', 15, 30, 'Leaf Ltd'), ('Cake', 8, 10, 'Sweet Bakery');

INSERT INTO deliveries (product, quantity, arrived_at) VALUES
    ('Coffee', 100, NOW() - INTERVAL 3 DAY), ('Tea', 50, NOW() - INTERVAL 10 DAY), ('Cake', 20, NOW() - INTERVAL 1 DAY);
