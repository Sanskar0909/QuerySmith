-- Warehouse: mark one of the newest orders as shipped, if it is still pending.
\set oid random(1960001, 2000000)
UPDATE orders
SET status = 'shipped'
WHERE id = :oid AND status = 'pending';
