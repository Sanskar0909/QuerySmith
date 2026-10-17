# The lab

A Postgres database holding a made-up online shop, plus a script that plays the
shop's traffic against it. Twelve kinds of request: ten reads that are each
slow, or look slow, for a different reason, and two writes.

It is what QuerySmith is pointed at during development, and what its output is
marked against (`lab/ANSWERS.md`).

## Using it

Needs Docker. Run from the project folder.

```bash
docker compose up -d --build --wait                               # start, wait until ready
docker compose exec db psql -U postgres -d shop -f /lab/seed.sql  # fill it (about 15 s)
lab/workload/run.sh                                               # play 60 s of traffic
lab/psql.sh                                                       # open a prompt as postgres
lab/psql.sh querysmith_ro                                         # ... or as the read-only login
docker compose down                                               # stop, keep the data
docker compose down -v                                            # stop and throw the data away
```

`lab/TOUR.md` walks through the lab by hand in about an hour.

## Where things are

| Path | What it is |
|---|---|
| `docker-compose.yml` | starts the database; every setting is commented |
| `db/Dockerfile` | stock Postgres 18 plus the HypoPG package |
| `db/init/01-extensions.sql` | switches on `pg_stat_statements` and HypoPG |
| `db/init/02-schema.sql` | the five tables |
| `db/init/03-roles.sql` | the logins and what each may do |
| `lab/seed.sql` | fills the tables; safe to run again |
| `lab/workload/*.sql` | one file per kind of request the shop makes |
| `lab/workload/run.sh` | plays the traffic |
| `lab/psql.sh` | opens a database prompt |
| `lab/TOUR.md` | a guided walk-through, done by hand |
| `lab/ANSWERS.md` | what is wrong with each query. Closed until attempted |

Versions as built: PostgreSQL 18.6, HypoPG 1.4.3, pg_stat_statements 1.12.

## How it is set up, and why

**Postgres runs in Docker, on port 5433.** Nothing is installed on the machine
itself, and a full reset is one command. 5433 avoids a clash with another
Postgres on the usual 5432. The port is bound to `127.0.0.1`, so nothing outside
the machine can reach it.

**`pg_stat_statements` is loaded at server start.** It has to be: it is listed
in `shared_preload_libraries` in the compose file. Creating the extension
without that line gives an error the first time the list is read.

**Parallel workers and JIT are switched off.** Both are on by default in real
Postgres. With parallel workers on, a scan of a large table can show as a
`Gather` step over a `Parallel Seq Scan`, with numbers reported per worker
instead of in total. They are off so the first plans are readable, and they
come back on at M4, where reading those plans is the point. Parallel workers
usually make large scans faster, so lab timings are probably higher than a
default Postgres would show. That was not measured.

**Three logins.**

| Login | May | Used for |
|---|---|---|
| `postgres` | everything | creating and dropping indexes by hand |
| `shop_app` | read, insert, update | the workload, so the slow queries belong to "the application" |
| `querysmith_ro` | read only | the tool |

`querysmith_ro` has `SELECT` on the tables and membership of the built-in role
`pg_read_all_stats`, and nothing else.
- It needs `SELECT` because timing a query means reading what the query reads.
- It needs `pg_read_all_stats` because without it the text of other logins'
  queries shows as `<insufficient privilege>`. `shop_app`, which lacks the role,
  sees exactly that.
- It has no timeouts or read-only default set on it. Where those belong is the
  design question of M3, so they were left out on purpose.

Confirmed refusals for `querysmith_ro`: `UPDATE` (permission denied),
`CREATE INDEX` (must be owner), `pg_stat_statements_reset()` (permission denied).

The passwords are trivial on purpose. The database only listens on the local
machine and holds made-up data.

**The data is uneven on purpose.** Even data makes every plan obvious. Real
data is lopsided, and lopsided data is where a database's choices get
interesting.

| Table | Rows | Unevenness |
|---|---|---|
| `customers` | 100,000 | none |
| `products` | 10,000 | none |
| `orders` | 2,000,000 | by customer and by status, below |
| `order_items` | 6,000,000 | 1 to 5 per order |
| `support_tickets` | 302,000 | 2,000 open, 300,000 closed |

Orders per customer fall off steeply with the customer id:

| Customer | Orders |
|---|---|
| 1 | 43,405 |
| 2 | 11,081 |
| 42 | 1,223 |
| 4242 | 51 |
| 99000 | 7 |

Order status:

| Status | Orders | Share | Where |
|---|---|---|---|
| delivered | 1,862,217 | 93.1% | everywhere except the newest 40,000 |
| cancelled | 78,272 | 3.9% | same |
| shipped | 20,009 | 1.0% | only the newest 40,000 |
| pending | 19,991 | 1.0% | only the newest 40,000 |
| refunded | 19,511 | 1.0% | everywhere except the newest 40,000 |

Orders are stored oldest first, 2,000 a day for 1,000 days from 2024-01-01. A
higher id is always a later order. Pending and shipped orders only exist at the
recent end, the way they would in a real shop.

**The seed is repeatable.** It calls `setseed(0.42)` first, so the same rows
come out every time and the counts above are exact. The *guesses* in plans
still move by a row or two between seeds (`rows=41` one time, `rows=42` the
next), because Postgres builds its statistics from a random sample.

**Foreign-key checks are skipped during the load** (`session_replication_role = replica`).
The generated data is valid by construction. The constraints themselves are
real and enforced for everything after the load.

**One deliberate contrivance: `support_tickets` has automatic statistics
refresh switched off.** Postgres would otherwise quietly repair one of the
planted problems soon after the seed finishes. This is the only place the lab
is rigged against the database's normal behaviour.

**The traffic is played with `pgbench`.** It is Postgres's own load generator
and is already inside the image. Four clients run at once. Each picks a request
at random, weighted so the product listing is common and the finance reports
are rare, waits for the answer, and picks again.
- Values vary from call to call (`\set cid random(...)`), so the query list
  shows `$1` standing in for many different values, as it would in production.
- Busy customers are picked more often for the "my orders" page.
- Dates are passed as seconds since 1970 and converted with `to_timestamp()`,
  because `pgbench` variables can only hold numbers.
- The two writes add and change a few hundred orders per minute. Counts drift
  slightly after the workload has run; re-running the seed puts them back.

**Why there are writes at all.** Real query lists contain them, and running
`EXPLAIN ANALYZE` on a write performs the write. The tool has to recognise them
and leave them alone. They are there to be refused.

**What each planted query is there to show** is in `lab/ANSWERS.md`. Without
spoilers: some are fixed by an index, some are not, one is fixed by an index
that harms a different query, and one looks alarming and is not worth fixing.

## Behaviour the tool depends on

Each of these was checked against the lab.

1. **HypoPG is packaged for Postgres 18** (`postgresql-18-hypopg`).
2. **The read-only login can create pretend indexes**, including inside a
   transaction opened with `BEGIN TRANSACTION READ ONLY`. A pretend index
   writes nothing to the database, so "read-only" does not block it.
3. **A pretend index shows up in `EXPLAIN` and not in `EXPLAIN ANALYZE`.** Same
   query, same connection: cost 41,668 -> 82 with plain `EXPLAIN`, and back to a
   full `Seq Scan` at 80 ms with `ANALYZE`.
4. **A pretend index survives `ROLLBACK`.** It lives in the connection's memory,
   not in the transaction, and stays until `hypopg_reset()` is called or the
   connection closes. Matters for M5: a leftover pretend index from one test
   would silently change the result of the next.
5. **HypoPG's size figure is an estimate too.** It predicted 78 MB for an index
   that came out at 60 MB when built for real.
6. **Pretend indexes can be partial, on an expression, and can `INCLUDE`
   columns.** So those kinds of suggestion are testable.
7. **`EXPLAIN (GENERIC_PLAN)` accepts `$1`; `EXPLAIN ANALYZE` does not**
   (`ERROR: there is no parameter $1`). M3 starts here.
8. **Query text in `pg_stat_statements` keeps its line breaks.** A query
   written over five lines is stored over five lines. Anything that prints or
   compares query text has to cope with that.
9. **The administrator's own statements land in the list too.** Every
   `CREATE INDEX` and `EXPLAIN` typed by hand is recorded, under the `postgres`
   login. Matters for M2.

## Timings

On a laptop: seeding takes about 15 seconds and the database is about 700 MB on
disk. Reading all of `orders` takes roughly 40–65 ms and all of `order_items`
roughly 140 ms, so the slow queries are slow enough to see and fast enough not
to wait for.

## What would break it

| If you | Then | Put it right with |
|---|---|---|
| edit a file in `db/init/` | nothing happens: those run only on an empty database | `docker compose down -v`, then start and seed again |
| change `FROM postgres:18` in the Dockerfile | the build fails unless the HypoPG package name changes to match | keep the two version numbers the same |
| remove the `shared_preload_libraries` line | reading `pg_stat_statements` errors | put it back and restart |
| run `ANALYZE support_tickets` or a database-wide `ANALYZE` | one planted problem disappears | re-run the seed |
| leave an index behind after an experiment | other queries' plans change, and the answer key stops matching | `DROP INDEX`, or `docker compose down -v` |
| run the workload many times without re-seeding | counts in the tour and answer key drift | re-run the seed |
| start something else on port 5433 | the lab will not start | change the port in `docker-compose.yml` |

Re-running the seed restores the data and clears the query list. It does not
remove indexes. Only `docker compose down -v` gives a truly clean lab.
