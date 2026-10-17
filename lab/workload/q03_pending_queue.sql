-- Warehouse screen: the 50 orders that have been waiting longest.
SELECT id, customer_id, created_at
FROM orders
WHERE status = 'pending'
ORDER BY created_at
LIMIT 50;
