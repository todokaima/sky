-- loyalty (MySQL, localhost:3307, teo / kaima): who the customers are and the points their orders earned
CREATE TABLE customers (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(80)  NOT NULL,
    email      VARCHAR(120) NOT NULL UNIQUE,
    tier       ENUM('bronze', 'silver', 'gold') NOT NULL DEFAULT 'bronze',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE points (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT NOT NULL,
    product     VARCHAR(40) NOT NULL,
    points      INT NOT NULL,
    earned_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customers (id)
);

INSERT INTO customers (name, email, tier) VALUES
    ('Ada Lovelace',   'ada@example.com',   'gold'),
    ('Alan Turing',    'alan@example.com',  'silver'),
    ('Grace Hopper',   'grace@example.com', 'gold'),
    ('Linus Torvalds', 'linus@example.com', 'bronze');

INSERT INTO points (customer_id, product, points) VALUES
    (1, 'Coffee', 35), (1, 'Cake', 48), (2, 'Tea', 22), (3, 'Coffee', 70), (3, 'Coffee', 35), (4, 'Cake', 48);
