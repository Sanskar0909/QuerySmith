-- Finance dashboard: total revenue from delivered orders.
SELECT sum(total_cents)
FROM orders
WHERE status = 'delivered';
