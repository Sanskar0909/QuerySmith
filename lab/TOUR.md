# Lab tour — do it all by hand

**Goal:** make a slow query fast yourself, and be able to say why it got faster.
No Java. Just you, a database, and nine steps.

Everything QuerySmith will do later, you do once by hand here. When you write
the tool, you will be automating things you have already seen work.

**Time:** about an hour.

**The times below were measured on a laptop on 2026-10-13.** Yours will be close but
not identical, and they change a little from run to run. Row counts should
match exactly.

---

## You are done when

- [ ] You ran a query that took about 45 ms and made it take under 1 ms
- [ ] You can point at the line in a plan that means "Postgres read every row"
- [ ] You can say what `EXPLAIN` tells you and what only `EXPLAIN ANALYZE` tells you
- [ ] You watched a pretend index appear in `EXPLAIN` and vanish in `EXPLAIN ANALYZE`
- [ ] You built an index Postgres refused to use, and can say why it refused
- [ ] You saw `$1` in the recorded query list

---

## Step 0 — Start the lab

Run these from the project folder, in your normal terminal.

```bash
docker compose up -d --build --wait
docker compose exec db psql -U postgres -d shop -f /lab/seed.sql
```

The first line starts Postgres and waits until it is ready. The second fills it
with a made-up online shop and takes about 15 seconds. It ends by printing the
tables:

```
   table_name    |   rows    | size_on_disk
-----------------+-----------+--------------
 order_items     | 6,000,000 | 473 MB
 orders          | 2,000,000 | 173 MB
 support_tickets | 302,000   | 29 MB
 customers       | 100,000   | 10 MB
 products        | 10,000    | 864 kB
```

Now open a prompt inside the database:

```bash
lab/psql.sh
```

You should see `shop=#`. Everything from here until Step 9 is typed at that
prompt. `\q` leaves it.

---

## Step 1 — Look at the table

```
\d orders
```

**Notice:** near the bottom, under `Indexes:`, there is exactly one line:

```
Indexes:
    "orders_pkey" PRIMARY KEY, btree (id)
```

An index is a sorted lookup structure, like the index at the back of a book.
This table has one, on `id`. So Postgres can find an order quickly by its `id`,
and by nothing else.

---

## Step 2 — Run a slow query

This is the shop's "my orders" page: one customer's 20 most recent orders.

```sql
\timing on

SELECT id, status, total_cents, created_at
FROM orders
WHERE customer_id = 4242
ORDER BY created_at DESC
LIMIT 20;
```

**Notice:** the last line. Something like `Time: 45.959 ms`.

45 ms sounds fast. But it only had to find 20 rows, and if a thousand people
opened this page every second, the database would need 45 seconds of work for
each second that passes.

---

## Step 3 — Ask Postgres how it would run it

Put the word `EXPLAIN` in front of the same query.

```sql
EXPLAIN
SELECT id, status, total_cents, created_at
FROM orders
WHERE customer_id = 4242
ORDER BY created_at DESC
LIMIT 20;
```

```
 Limit  (cost=41668.12..41668.17 rows=20 width=29)
   ->  Sort  (cost=41668.12..41668.22 rows=42 width=29)
         Sort Key: created_at DESC
         ->  Seq Scan on orders  (cost=0.00..41667.00 rows=42 width=29)
               Filter: (customer_id = 4242)
```

This is a **query plan**: the steps Postgres will take. Read it from the bottom
up, most-indented line first.

1. `Seq Scan on orders` — read the orders table from start to end. "Seq" is
   short for sequential. The `Filter` line says which rows it keeps.
2. `Sort` — sort what is left by date.
3. `Limit` — hand back the first 20.

**Notice three things:**

- It came back in well under a millisecond. `EXPLAIN` does not run the query.
  It only asks for the plan.
- `rows=42` on the Seq Scan line is a **guess**. Postgres thinks about 42 orders
  will match. Yours may say 41 or 43.
- `cost=...41667.00` is also a guess, in made-up units. It is not milliseconds.
  The only use of a cost is comparing it with another cost.

---

## Step 4 — Run it and measure

Now `EXPLAIN ANALYZE`. This one **does** run the query, and reports what
really happened next to what was guessed.

```sql
EXPLAIN ANALYZE
SELECT id, status, total_cents, created_at
FROM orders
WHERE customer_id = 4242
ORDER BY created_at DESC
LIMIT 20;
```

The line to read first:

```
 ->  Seq Scan on orders  (cost=0.00..41667.00 rows=42 width=29) (actual time=1.674..41.087 rows=51.00 loops=1)
       Filter: (customer_id = 4242)
       Rows Removed by Filter: 1999949
```

and at the very bottom:

```
 Execution Time: 41.113 ms
```

**Notice:**

- Each step now has two brackets. The first is the guess from Step 3. The
  second, starting `actual`, is what happened.
- Guess: `rows=42`. Actual: `rows=51.00`. A good guess.
- `Rows Removed by Filter: 1999949`. Postgres read two million rows and threw
  away all but 51. **This is the line that means "read every row".**
- Almost all of the 41 ms is on that one step.

Ignore the lines that start `Buffers:` and `Planning:` for now. They come back
in a later milestone.

**Question 1:** the Seq Scan's actual time is `1.674..41.087`. What do you think
the two numbers are?

---

## Step 5 — Add an index, then measure again

```sql
CREATE INDEX orders_customer_idx ON orders (customer_id);
```

That took about 0.3 seconds. Now run the exact `EXPLAIN ANALYZE` from Step 4
again (press the up arrow until you find it).

```
 ->  Bitmap Heap Scan on orders  (cost=4.75..166.95 rows=42 width=29) (actual time=0.091..0.318 rows=51.00 loops=1)
       Recheck Cond: (customer_id = 4242)
       ->  Bitmap Index Scan on orders_customer_idx  (cost=0.00..4.74 rows=42 width=0) (actual time=0.025..0.025 rows=51.00 loops=1)
             Index Cond: (customer_id = 4242)
 ...
 Execution Time: 0.341 ms
```

**Notice:**

- `Seq Scan` is gone. In its place are two steps that mention your index by
  name. First it looks up customer 4242 in the index and gets the locations of
  51 rows. Then it fetches just those rows from the table.
- No `Rows Removed by Filter`. It never touched the other 1,999,949.
- 41 ms became 0.34 ms. About 120 times faster.
- The cost guess fell too, from 41668 to 168.

You have now done the whole job QuerySmith exists to do: found a slow query,
read its plan, tried a fix, and measured the before and after.

---

## Step 6 — What the index costs

```
\di+ orders_customer_idx
```

**Notice:** the `Size` column says `15 MB`.

An index is a second copy of one column, kept sorted. That has two prices:

- **Disk:** 15 MB here. The table's own data is about 130 MB.
- **Writes:** every new order now has to be added to the index too, in the
  right place. Every insert into `orders` got a little slower, forever.

An index is only worth it if the reads it speeds up matter more than the writes
it slows down. A report that shows the gain and hides the price is not honest.

---

## Step 7 — A pretend index

Building a real index on a large live table can take minutes and slow the
database down while it builds. HypoPG lets you ask "would Postgres use this?"
without building anything.

Remove the real index, and create a pretend one:

```sql
DROP INDEX orders_customer_idx;

SELECT * FROM hypopg_create_index('CREATE INDEX ON orders (customer_id)');
```

Now plain `EXPLAIN` (no ANALYZE):

```sql
EXPLAIN
SELECT id, status, total_cents, created_at
FROM orders
WHERE customer_id = 4242
ORDER BY created_at DESC
LIMIT 20;
```

```
 ->  Bitmap Index Scan on "<13640>btree_orders_customer_id"  (cost=0.00..4.37 rows=42 width=0)
```

**Notice:** Postgres planned to use an index that does not exist. The cost guess
at the top of the plan is about 168 again, almost the same as with the real one.

Now the same query with `EXPLAIN ANALYZE`:

```sql
EXPLAIN ANALYZE
SELECT id, status, total_cents, created_at
FROM orders
WHERE customer_id = 4242
ORDER BY created_at DESC
LIMIT 20;
```

**Notice:** `Seq Scan` is back, and so is the 41 ms. To actually run the query
Postgres needs a real index to read, and there is none.

This is the most important limit in the whole project:

- A pretend index gives you an **estimate**: "Postgres would use it, cost 41668 -> 168."
- Only a real index gives you a **measurement**: "41 ms -> 0.34 ms."

Clean up:

```sql
SELECT hypopg_reset();
```

**Question 2:** the estimate said cost would fall from 41668 to 168, about 250
times. The measurement in Step 5 was about 120 times. Why might an estimate and
a measurement disagree?

---

## Step 8 — An index Postgres will not use

A finance dashboard adds up revenue from delivered orders. It is slow, and it
filters on `status`, so an index on `status` looks like the obvious fix.

```sql
CREATE INDEX orders_status_idx ON orders (status);

EXPLAIN ANALYZE
SELECT sum(total_cents) FROM orders WHERE status = 'delivered';
```

```
 ->  Seq Scan on orders  (cost=0.00..41667.00 rows=1859000 width=4) (actual time=0.083..81.039 rows=1862217.00 loops=1)
       Filter: (status = 'delivered'::text)
       Rows Removed by Filter: 137783
```

**Notice:** still `Seq Scan`. Your index exists and Postgres ignored it.

Now change one word:

```sql
EXPLAIN ANALYZE
SELECT sum(total_cents) FROM orders WHERE status = 'refunded';
```

```
 ->  Index Scan using orders_status_idx on orders  (cost=0.43..9986.29 rows=22067 width=4) (actual time=0.036..31.466 rows=19511.00 loops=1)
       Index Cond: (status = 'refunded'::text)
```

**Notice:** same query, same index, different value, different plan.

Look at the `rows=` numbers. 'delivered' matches 1,862,217 of 2,000,000 orders:
93% of the table. 'refunded' matches 19,511: about 1%.

An index helps when it lets Postgres **skip** most of the table. If the answer
needs 93% of the rows, looking each one up through an index is slower than just
reading the lot, and Postgres knows it.

So "this column is in the WHERE, add an index on it" is not a rule. It depends
on the data, and on the value being asked for.

Clean up:

```sql
DROP INDEX orders_status_idx;
```

**Question 3:** how did Postgres know, before running anything, that
'delivered' is 93% of the table?

---

## Step 9 — Where the tool will start

Until now you were told which query was slow. A real database does not tell
you. So Postgres keeps a list.

Leave psql with `\q`. In your normal terminal, play one minute of shop traffic:

```bash
lab/workload/run.sh
```

It prints a progress line every 10 seconds and a long summary at the end, which
you can ignore. Then open a prompt again, this time as the tool's read-only
login:

```bash
lab/psql.sh querysmith_ro
```

Note the prompt is now `shop=>` instead of `shop=#`. The `#` meant administrator.

```sql
SELECT calls,
       round(total_exec_time) AS total_ms,
       round(mean_exec_time::numeric, 1) AS mean_ms,
       left(regexp_replace(query, '\s+', ' ', 'g'), 70) AS query
FROM pg_stat_statements
WHERE userid = 'shop_app'::regrole;
```

**Notice:**

- One row per *kind* of query, not per run. `calls` says how many times it ran.
- Where the values were, there is `$1`, `$2`. The "my orders" query ran hundreds
  of times with different customer ids, and all of them were folded into one row
  reading `WHERE customer_id = $1`. The actual ids are gone.
- `total_ms` is all the runs added up. `mean_ms` is one run on average.

Try this as the read-only login. It should refuse:

```sql
UPDATE orders SET status = 'shipped' WHERE id = 1;
```

**Question 4:** which query in that list is the worst? Find two different
answers, each with a reason. There is no single right one. This is the first
design decision of the next milestone.

---

## What you just did

| You did by hand | QuerySmith will do it as |
|---|---|
| Step 9: read the query list | collect and rank |
| Steps 3 and 4: `EXPLAIN`, `EXPLAIN ANALYZE` | explain |
| Step 4: found "Rows Removed by Filter" | read the plan |
| Step 7: pretend index | test, **estimated** |
| Step 5: real index, timed before and after | test, **measured** |
| Step 6: size and write cost | the price of the fix |
| Step 8: an index that was not used | a **rejected** suggestion |

One thing you could not do in Step 9 and the tool must: run a query whose
values have been replaced by `$1`. Try it and read the error:

```sql
EXPLAIN ANALYZE SELECT id FROM orders WHERE customer_id = $1;
```

---

## Starting over

To put the data back exactly as it was (does not remove indexes you created):

```bash
docker compose exec db psql -U postgres -d shop -f /lab/seed.sql
```

To throw everything away and start from an empty database:

```bash
docker compose down -v
```

then Step 0 again.

---

## Answers

Try each question before reading these.

**Question 1.** The first number is the time until the step produced its first
row; the second is the time until it produced its last. So `1.674..41.087` means
the scan found its first matching order after 1.7 ms and finished after 41 ms.
The second number is the one that tells you how long the step took.

**Question 2.** The cost is a prediction made from statistics, in units that are
not time. It assumes things about your disk, your memory and your data that may
not hold. It is usually right about *direction* (this plan is cheaper than that
one) and often wrong about *size*. Sometimes it is wrong about direction too,
and one of the planted problems in this lab is exactly that. This is why the
report labels every number as estimated or measured.

**Question 3.** Postgres keeps statistics about every column: roughly how many
rows, and which values are most common and how common. It collected them when
the seed script ran `ANALYZE`. You can look at its notes:

```sql
SELECT most_common_vals, most_common_freqs
FROM pg_stats
WHERE tablename = 'orders' AND attname = 'status';
```

Every guess in every plan comes from notes like these. When the notes are out
of date, the guesses are wrong, and the plan can be badly wrong with them.

**Question 4.** Two defensible answers, and they are different queries:

- By `total_ms`: the query that used the most database time overall. Fixing it
  frees the most capacity.
- By `mean_ms`: the query that is slowest each time it runs. Fixing it helps the
  one user who is waiting for it the most.

A third: by `calls`. One query is very fast but runs far more often than
anything else. Is that a problem? It might be.
