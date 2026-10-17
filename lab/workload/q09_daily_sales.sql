-- Sales report: number of orders and revenue for one day.
\set day random(0, 999)
\set t0 1704067200 + :day * 86400
\set t1 :t0 + 86400
SELECT count(*), sum(total_cents)
FROM orders
WHERE created_at >= to_timestamp(:t0)
  AND created_at < to_timestamp(:t1);
