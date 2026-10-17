-- Admin screen: browse all orders, 20 per page. Staff jump to any page.
\set page random(0, 75000)
\set skip :page * 20
SELECT id, customer_id, status, total_cents
FROM orders
ORDER BY id
LIMIT 20 OFFSET :skip;
