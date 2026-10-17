-- "My orders" page: one customer's 20 most recent orders.
-- Busy customers open this page more often, so low ids are picked more.
\set cid random_zipfian(1, 100000, 1.07)
SELECT id, status, total_cents, created_at
FROM orders
WHERE customer_id = :cid
ORDER BY created_at DESC
LIMIT 20;
