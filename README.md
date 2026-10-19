# QuerySmith

A command-line tool that connects to a Postgres database with a read-only
login, finds the most expensive queries, works out why they are slow, suggests
a fix, and tests the fix. The report carries evidence instead of opinion: every
number is labelled **estimated** (from a pretend index, via HypoPG) or
**measured** (from a real index, on a throwaway copy).

It is not limited to indexes. It diagnoses any cause it can read from a plan
(out-of-date statistics, a sort too big for memory, a tiny query called far too
often) and proves index fixes first, because an index can be tried without
changing the application or the data.

## What a report entry will look like

Numbers invented to show the shape:

```
#1  SELECT * FROM orders WHERE customer_id = $1 ORDER BY created_at DESC LIMIT $2
    38% of all database time · 1.2M calls · 41 ms each on average

    What is happening
      Postgres reads all 2,000,000 orders, throws away 1,999,949, then sorts the 51 left.

    Suggestion
      CREATE INDEX ON orders (customer_id, created_at DESC);

    Evidence
      Estimated (pretend index, live database)   planner would use it · cost 41,668 -> 82
      Measured  (real index, on a copy, 10 runs) 41.3 ms -> 0.07 ms
      Price of the index                         60 MB · every insert into orders gets slower
      Other top queries                          4 unchanged, 0 worse

    Rejected
      CREATE INDEX ON orders (status)            planner did not use it
```

## Status

| Milestone | | |
|---|---|---|
| M0 | Lab: a Postgres with planted slow queries | done |
| M1 | Every step done by hand, no code | done |
| M2 | `querysmith top`: collect and rank | done |
| M3 | Safe explain | next |
| M4 | Read the plan | |
| M5 | Test an index, estimated | |
| M6 | LLM explains and suggests | |
| M7 | Test an index, measured | |
| M8 | Ship | |

## Try the lab

Needs Docker.

```bash
docker compose up -d --build --wait
docker compose exec db psql -U postgres -d shop -f /lab/seed.sql
lab/workload/run.sh
lab/psql.sh querysmith_ro
```

Then follow `lab/TOUR.md`.

## Run `querysmith top`

Needs Java 25 or newer. Maven comes through its wrapper, and the tests need
Docker.

```bash
./mvnw -q clean package            # build, and run the tests
export QUERYSMITH_DB='jdbc:postgresql://localhost:5433/shop?user=querysmith_ro&password=querysmith_ro'
./querysmith top                   # the ten queries with the most time in total
./querysmith top --user shop_app --top 5
./querysmith top --help
```

From the lab after one minute of traffic:

```
Connected as querysmith_ro to shop (PostgreSQL 18.6)

Queries by total time, worst first. Share is out of all the time recorded in this
database since pg_stat_statements was last reset.

 #  share     total   calls  per call  login     query
 1  39.3%    92.3 s     480  192.3 ms  shop_app  SELECT product_id, quantity, unit_price_cents FROM order_items WHERE order_id...
 2  20.2%    47.3 s     710   66.7 ms  shop_app  SELECT id, status, total_cents, created_at FROM orders WHERE customer_id = $1...
 3  14.2%    33.4 s      53  630.1 ms  shop_app  SELECT c.email, count(*) AS closed_tickets FROM support_tickets AS t JOIN cus...
 ...
10   0.1%  278.2 ms  73,480  0.004 ms  shop_app  SELECT name, price_cents FROM products WHERE id = $1  [possible N+1]
```

The third query is three times slower per run than the first, but runs about
a ninth as often, so it ranks lower. The tenth costs almost nothing per run and
is flagged anyway: called 73,480 times in a minute, it is most likely one
query per row of another query's result.

Connected with a login that can change data, the tool refuses to run, names
the tables that login could change, and prints the SQL for a read-only login.

## Docs

| File | What it covers |
|---|---|
| `docs/DESIGN.md` | the product, the milestones, every decision and its reason |
| `docs/LAB.md` | what is in the lab, how it is set up and why, what would break it |
| `lab/TOUR.md` | a one-hour walk-through of the lab, by hand |
| `lab/ANSWERS.md` | what is wrong with each planted query; the tool is marked against it |

## Stack

PostgreSQL 18 with `pg_stat_statements` and HypoPG. The tool: Java 25, pgjdbc
and picocli, built with Maven through its wrapper. Tests: JUnit 6, against a
real Postgres started by Testcontainers. Jackson joins at M4, to read plans.
