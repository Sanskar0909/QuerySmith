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
| M2 | `querysmith top`: collect and rank | next |
| M3 | Safe explain | |
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

## Docs

| File | What it covers |
|---|---|
| `docs/DESIGN.md` | the product, the milestones, every decision and its reason |
| `docs/LAB.md` | what is in the lab, how it is set up and why, what would break it |
| `lab/TOUR.md` | a one-hour walk-through of the lab, by hand |
| `lab/ANSWERS.md` | what is wrong with each planted query; the tool is marked against it |

## Stack

PostgreSQL 18 with `pg_stat_statements` and HypoPG. The tool itself, from M2:
Java, pgjdbc, picocli, Jackson, JUnit 5 with Testcontainers, Maven.
