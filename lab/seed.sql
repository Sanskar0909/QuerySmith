-- Fills the shop with made-up data. Safe to run again: it empties the tables
-- first. Takes about 15 seconds.
--
--   docker compose exec db psql -U postgres -d shop -f /lab/seed.sql
--
-- The data is uneven on purpose. Real data is uneven, and uneven data is what
-- makes a database's choices interesting:
--   - a few customers have tens of thousands of orders, most have a handful
--   - almost every order is 'delivered'; 'pending' and 'shipped' only exist
--     among the newest orders, the way they would in a real shop
--   - orders are stored in the order they were placed, oldest first

\set ON_ERROR_STOP on
\timing on

-- Same seed, same data, every time. Row counts in the tour and the answer key
-- rely on this.
SELECT setseed(0.42);

TRUNCATE customers, products, orders, order_items, support_tickets RESTART IDENTITY CASCADE;

-- Skip the foreign-key check on every inserted row. The data below is valid by
-- construction, and a bulk load has no need to look up 8 million parent rows
-- one at a time. This setting lasts for this session only.
SET session_replication_role = replica;

\echo '--- customers: 100,000'
-- Emails are stored in mixed case (User42@Example.com), the way people type them.
INSERT INTO customers (id, email, full_name, created_at)
SELECT g,
       'User' || g || '@Example.com',
       'Customer ' || g,
       timestamptz '2023-01-01 00:00:00+00' + g * interval '5 minutes'
FROM generate_series(1, 100000) AS g;

\echo '--- products: 10,000'
INSERT INTO products (id, name, price_cents)
SELECT g,
       'Product ' || g,
       100 + floor(random() * 49900)::int
FROM generate_series(1, 10000) AS g;

\echo '--- orders: 2,000,000'
-- customer_id: power(random(), 3) piles the orders onto low customer ids.
--   Customer 1 ends up with about 43,000 orders, customer 99,000 with about 7.
-- status: the newest 40,000 orders are half 'pending', half 'shipped'.
--   Everything older is 95% 'delivered', 4% 'cancelled', 1% 'refunded'.
-- created_at: one order every 43.2 seconds from 2024-01-01, which is exactly
--   2,000 a day for 1,000 days. A higher id is always a later order.
INSERT INTO orders (id, customer_id, status, total_cents, created_at)
SELECT g,
       1 + floor(100000 * power(r1, 3))::bigint,
       CASE
           WHEN g > 1960000 THEN CASE WHEN r2 < 0.5 THEN 'pending' ELSE 'shipped' END
           WHEN r2 < 0.95 THEN 'delivered'
           WHEN r2 < 0.99 THEN 'cancelled'
           ELSE 'refunded'
       END,
       500 + floor(r3 * 99500)::int,
       timestamptz '2024-01-01 00:00:00+00' + g * interval '43.2 seconds'
FROM (
    SELECT g, random() AS r1, random() AS r2, random() AS r3
    FROM generate_series(1, 2000000) AS g
) AS s;

\echo '--- order_items: 6,000,000'
-- Between 1 and 5 items per order, 3 on average. Stored in order_id order.
-- The order total above is not the sum of these lines; nothing in the lab
-- depends on the two agreeing.
INSERT INTO order_items (order_id, product_id, quantity, unit_price_cents)
SELECT o.id,
       1 + floor(random() * 10000)::bigint,
       1 + floor(random() * 3)::int,
       100 + floor(random() * 49900)::int
FROM orders AS o
CROSS JOIN LATERAL generate_series(1, 1 + (o.id % 5)::int) AS n
ORDER BY o.id;

\echo '--- support_tickets: 2,000 open, then statistics, then 300,000 closed'
INSERT INTO support_tickets (id, customer_id, status, subject, created_at)
SELECT g,
       1 + floor(random() * 100000)::bigint,
       'open',
       'Ticket ' || g,
       timestamptz '2026-09-01 00:00:00+00' + g * interval '10 minutes'
FROM generate_series(1, 2000) AS g;

-- ANALYZE (on its own, not EXPLAIN ANALYZE) makes Postgres sample a table and
-- write down what it found: how many rows, which values are common. It plans
-- every query from those notes. At this moment the notes say "2,000 tickets,
-- all open".
ANALYZE support_tickets;

-- Then an archive of old tickets is imported, and nobody runs ANALYZE again.
INSERT INTO support_tickets (id, customer_id, status, subject, created_at)
SELECT g,
       1 + floor(random() * 100000)::bigint,
       'closed',
       'Ticket ' || g,
       timestamptz '2024-01-01 00:00:00+00' + g * interval '4 minutes'
FROM generate_series(2001, 302000) AS g;

SET session_replication_role = DEFAULT;

-- Ids were given explicitly above, so tell each id counter where to carry on.
SELECT setval(pg_get_serial_sequence('customers', 'id'), 100000);
SELECT setval(pg_get_serial_sequence('products', 'id'), 10000);
SELECT setval(pg_get_serial_sequence('orders', 'id'), 2000000);
SELECT setval(pg_get_serial_sequence('support_tickets', 'id'), 302000);

\echo '--- housekeeping'
-- VACUUM tidies up after the bulk load so the first query you time is not
-- also paying for cleanup. ANALYZE refreshes the planner's notes.
-- support_tickets gets the tidy-up but NOT the refresh. That is the point of it.
VACUUM (ANALYZE) customers, products, orders, order_items;
VACUUM support_tickets;

-- Start the query log from zero, so it shows the shop's traffic and not this script.
SELECT pg_stat_statements_reset() IS NOT NULL AS query_log_cleared;

\timing off
\echo '--- done'
-- Exact counts, and size on disk including each table's primary-key index.
SELECT t.table_name,
       to_char(t.row_count, 'FM9,999,999') AS rows,
       pg_size_pretty(pg_total_relation_size(t.table_name::regclass)) AS size_on_disk
FROM (
    SELECT 'order_items' AS table_name, count(*) AS row_count FROM order_items
    UNION ALL SELECT 'orders', count(*) FROM orders
    UNION ALL SELECT 'support_tickets', count(*) FROM support_tickets
    UNION ALL SELECT 'customers', count(*) FROM customers
    UNION ALL SELECT 'products', count(*) FROM products
) AS t
ORDER BY pg_total_relation_size(t.table_name::regclass) DESC;
