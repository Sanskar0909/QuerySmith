-- Order detail page: the lines of one order.
\set oid random(1, 2000000)
SELECT product_id, quantity, unit_price_cents
FROM order_items
WHERE order_id = :oid;
