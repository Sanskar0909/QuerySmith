# Answer key

**Closed until you have tried.** For each query in `lab/workload/`, work out what
is wrong from its plan first, then check here. Reading this first costs you the
part of the project that is worth the most.

Later this file has a second job: it is what QuerySmith's output is marked
against. If the tool disagrees with this page, one of them is wrong.

Every number here was measured on a laptop on 2026-10-13, on a freshly
seeded lab with nothing else running, one run each unless it says otherwise.
Treat times as "roughly". Row counts and plan shapes should match exactly.

---

## Summary

| Query | What it is | What is wrong | Right verdict |
|---|---|---|---|
| q01 | a customer's recent orders | no index on `customer_id` | add an index (two columns is best) |
| q02 | the lines of one order | no index on `order_id` | add an index |
| q03 | oldest pending orders | reads 2M rows to find 20,000 | add a *partial* index |
| q04 | revenue of delivered orders | nothing an index can fix | **reject** the obvious index |
| q05 | customer by email | filter wraps the column in `lower()` | index the expression, **reject** the plain one |
| q06 | product listing | 20 tiny queries where 1 would do | not an index: change the app |
| q07 | finance export | sort spills to disk | reported, and the usual fix does **not** help |
| q08 | closed-ticket report | statistics are out of date | not an index: run `ANALYZE` |
| q09 | sales for one day | no index on `created_at` | add an index, **but it makes q03 slower** |
| q10 | orders, page N | `OFFSET` reads and discards | not an index: rewrite the query |
| w01, w02 | place / ship an order | nothing. They are writes | the tool must refuse to run them |

Five of the ten are not fixed by an index. That is deliberate.

---

## q01 — a customer's recent orders

```sql
SELECT id, status, total_cents, created_at FROM orders
WHERE customer_id = $1 ORDER BY created_at DESC LIMIT 20;
```

**Wrong:** `Seq Scan on orders`, `Rows Removed by Filter: 1999949`. A foreign
key does not create an index, so `customer_id` has none.

**Fix, and why the value matters:**

| Index | Size | Customer 4242 (51 orders) | Customer 1 (43,405 orders) |
|---|---|---|---|
| none | | 41–65 ms | 55 ms |
| `(customer_id)` | 15 MB | 0.33 ms | 24 ms |
| `(customer_id, created_at DESC)` | 60 MB | 0.07 ms | 0.03 ms |
| `(created_at DESC, customer_id)` | | 27 ms | 0.03 ms |

- The one-column index is enough for a small customer. For the biggest one it
  still fetches 43,405 rows and sorts them to keep 20.
- The two-column index stores each customer's orders already sorted by date, so
  Postgres reads the first 20 entries and stops.
- The same two columns in the other order are nearly useless for a small
  customer: Postgres walks the index newest-first, checking every entry, until
  it has found 20 of theirs.

A tool that tests with customer 4242 alone would call the 15 MB index good
enough. Testing with customer 1 shows why the 60 MB one exists.

## q02 — the lines of one order

```sql
SELECT product_id, quantity, unit_price_cents FROM order_items WHERE order_id = $1;
```

**Wrong:** `Seq Scan on order_items`, `Rows Removed by Filter: 5999997`. Same
cause as q01, on the largest table.

**Fix:** `CREATE INDEX ON order_items (order_id)`. 136 ms -> 0.05 ms. 84 MB.

This is the biggest single win in the lab, and the plainest.

## q03 — oldest pending orders

```sql
SELECT id, customer_id, created_at FROM orders
WHERE status = 'pending' ORDER BY created_at LIMIT 50;
```

**Wrong:** `Seq Scan`, `Rows Removed by Filter: 1980009`, to find 19,991 pending
orders and keep 50. 68 ms.

**Fix:**

| Index | Size | Time |
|---|---|---|
| `(created_at) WHERE status = 'pending'` | 456 kB | 0.02 ms |
| `(status, created_at)` | 77 MB | 0.03 ms |

Both work. The first is a *partial index*: it only contains the pending rows,
so it is 170 times smaller than the second and almost free to maintain. Only 1%
of orders are pending, and this query only ever asks about that 1%.

## q04 — revenue of delivered orders (the trap)

```sql
SELECT sum(total_cents) FROM orders WHERE status = 'delivered';
```

**Wrong:** nothing an index can fix. 93% of orders are delivered. The query
needs almost the whole table, and reading the whole table in order is the
fastest way to get it.

**Tested and rejected:**

| Index | Size | Used? |
|---|---|---|
| `(status)` | 13 MB | no |
| `(status) INCLUDE (total_cents)` | 60 MB | no |

Time stays at 100–160 ms either way.

The same `(status)` index *is* used for `status = 'refunded'` (1% of rows):
45 ms -> 7 ms. Same query, same index, different value.

**Right verdict:** reject. If the dashboard needs this number quickly, the fix
is outside the query: keep a running total, or compute it once a night.

## q05 — customer by email

```sql
SELECT id, full_name FROM customers WHERE lower(email) = $1;
```

**Wrong:** `Seq Scan on customers`, `Rows Removed by Filter: 99999`. 15–20 ms,
but it runs on every login.

**The obvious fix fails:** `CREATE INDEX ON customers (email)` is not used. The
index is sorted by `email`; the query asks about `lower(email)`, which is a
different value in a different order.

**Fix:** index the expression itself: `CREATE INDEX ON customers (lower(email))`.
0.02 ms. 4 MB.

Also visible in this plan: the guess is `rows=500`, the truth is 1. Postgres
keeps statistics about `email` but none about `lower(email)`, so it falls back
to a fixed default: half a percent of the table.

## q06 — product listing

```sql
SELECT name, price_cents FROM products WHERE id = $1;
```

**Wrong:** not the query. It uses the primary key and takes about 0.03 ms. The
problem is that the page runs it 20 times, once per product. In the query list
it has more calls than everything else combined.

**Right verdict:** no index. Change the app to ask once:
`WHERE id = ANY($1)` with all 20 ids. This is called the N+1 problem.

The tool cannot prove this from the database alone. It can report "very fast,
called far more than anything else" and say what that usually means.

## q07 — finance export

```sql
SELECT id, customer_id, total_cents FROM orders
WHERE created_at >= to_timestamp($1) ORDER BY total_cents DESC;
```

**In the plan:** `Sort Method: external merge  Disk: 14136kB`. The sort did not
fit in the 4 MB it is allowed (`work_mem`) and spilled to disk.

**The usual advice:** raise `work_mem`.

**Measured:** with `work_mem = '64MB'` the plan changes to
`Sort Method: quicksort  Memory: 31039kB`, and the time goes from 142 ms to
142 ms. No gain.

The alarming line was not where the time went. For the start date tested, the
query returns 480,001 rows. Reading them, sorting them and sending them is the
cost, on disk or in memory.

An index on `created_at` helps a little (142 ms -> 113 ms).

**Right verdict:** report the spill, and do not promise that fixing it helps.
This is the lab's argument for testing advice instead of repeating it.

## q08 — closed-ticket report

```sql
SELECT c.email, count(*) FROM support_tickets t JOIN customers c ON c.id = t.customer_id
WHERE t.status = 'closed' GROUP BY c.email ORDER BY count(*) DESC LIMIT 10;
```

**In the plan:**

```
Seq Scan on support_tickets t  (... rows=1 ...) (actual ... rows=300000.00 loops=1)
Index Scan using customers_pkey on customers c  (...) (actual time=0.000..0.000 rows=1.00 loops=300000)
```

**Wrong:** Postgres guessed 1 closed ticket. There are 300,000. Its notes about
this table were taken when it held 2,000 tickets, all open; 300,000 closed ones
were loaded afterwards and the notes were never refreshed.

Believing it had 1 row, it chose to look up customers one at a time. That is
the right plan for 1 row and a bad one for 300,000: `loops=300000`.

**Fix:** `ANALYZE support_tickets;`. 495 ms -> 89 ms. Postgres switches to
reading both tables once and matching them in memory (a hash join).

**How the tool can tell:** guess and actual differ by 300,000 times, and
`pg_stat_user_tables.n_mod_since_analyze` is far larger than the table was when
last analysed.

To try the fix without losing the planted problem:

```sql
BEGIN;
ANALYZE support_tickets;
EXPLAIN ANALYZE <the query>;
ROLLBACK;
```

## q09 — sales for one day, and the regression it causes

```sql
SELECT count(*), sum(total_cents) FROM orders
WHERE created_at >= to_timestamp($1) AND created_at < to_timestamp($2);
```

**Wrong:** `Seq Scan`, `Rows Removed by Filter: 1998000`, for one day's 2,000 orders.

**Fix:** `CREATE INDEX ON orders (created_at)`. 49 ms -> 0.35 ms. 43 MB.

**The catch.** That index changes the plan of q03:

| q03 | Estimated cost | Measured |
|---|---|---|
| before the index | 42,285 | 68 ms |
| after the index | 215 | 117–166 ms |

The estimate says q03 got 200 times cheaper. The measurement says it got about
twice as slow.

Why: with the new index Postgres walks orders oldest-first and expects to bump
into 50 pending ones almost immediately, because it assumes pending orders are
spread evenly through time. They are not. Every pending order is among the
newest 40,000. It reads and discards 1,960,046 rows on the way to its 50.

This is the one place in the lab where the estimate is wrong about *direction*.

**Right verdict:** accept the index for q09, and flag the regression on q03. A
tool that only checks the query it was asked about misses it. A tool that only
uses estimates reports it as an improvement.

## q10 — orders, page N

```sql
SELECT id, customer_id, status, total_cents FROM orders ORDER BY id LIMIT 20 OFFSET $1;
```

**In the plan (OFFSET 900000):** `Index Scan using orders_pkey ... rows=900020.00`.
It already uses an index. It still has to walk 900,020 entries to throw away
900,000 of them. 136 ms, and slower the deeper the page.

**Right verdict:** no index. Rewrite to continue from the last id shown:

```sql
SELECT ... FROM orders WHERE id > 900000 ORDER BY id LIMIT 20;
```

0.03 ms, the same on every page.

## w01, w02 — the writes

```sql
INSERT INTO orders (customer_id, status, total_cents, created_at) VALUES ($1, $2, $3, now());
UPDATE orders SET status = $1 WHERE id = $2 AND status = $3;
```

Nothing is wrong with them. They are in the query list because real query lists
contain writes, and `EXPLAIN ANALYZE` on a write **performs the write**.

**Right verdict:** the tool never runs these.

---

## Index suggestions the tool must reject

- `orders (status)` for q04 — not used
- `orders (status) INCLUDE (total_cents)` for q04 — not used
- `customers (email)` for q05 — not used

## Index suggestions the tool must accept with a warning

- `orders (created_at)` for q09 — works, and slows q03
