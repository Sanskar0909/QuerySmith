-- Finance export: every order since a start date, biggest first.
-- 1704067200 is 2024-01-01 00:00 UTC as seconds; the shop's first day.
\set day random(700, 820)
\set t0 1704067200 + :day * 86400
SELECT id, customer_id, total_cents
FROM orders
WHERE created_at >= to_timestamp(:t0)
ORDER BY total_cents DESC;
