-- Checkout: a customer places an order.
\set cid random(1, 100000)
\set total random(500, 100000)
INSERT INTO orders (customer_id, status, total_cents, created_at)
VALUES (:cid, 'pending', :total, now());
