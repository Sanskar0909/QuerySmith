# QuerySmith — design

Last updated 2026-10-19. When a decision here changes, this file changes with it.

## What it is

QuerySmith connects to a Postgres database with a read-only login, finds the
most expensive queries, works out why they are slow, suggests a fix, and tests
the fix so the report carries evidence instead of opinion.

The point is to show how a database actually runs a query: every claim in the
report is backed by a plan or a timing, not by a rule of thumb.

## The final product

A command-line tool. Point it at a database and it prints a report.

```
querysmith top     --db <url> --top 10          # list the most expensive queries
querysmith analyze --db <url> --top 5           # full report: explain, suggest, test
querysmith try     --db <url> "CREATE INDEX…"   # test an index idea of your own
```

One entry of the report (numbers invented to show the shape):

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

What the database needs: `pg_stat_statements` switched on, a read-only login,
and HypoPG installed for the "Estimated" line.

## What it fixes

It is not limited to indexes. `querysmith analyze` does two jobs.

1. **Diagnose, for any cause.** It runs the query, reads the whole plan, and
   says why it is slow: a missing index, out-of-date table statistics, a sort
   too big for memory, one small query called a huge number of times, an index
   nothing uses.
2. **Prove the fix, for indexes first.** For an index fix it shows a before and
   after. For other causes it names the cause and the usual fix, and marks the
   entry "not tested".

A non-index entry (numbers invented):

```
#3  SELECT * FROM orders ORDER BY total DESC
    What is happening
      The sort needed 180 MB but was allowed 4 MB, so it spilled to disk.
    Usual fix
      Raise work_mem for this query. An index would not help.
    Evidence
      Not tested, so the gain is unknown.
      Read from the plan: "Sort Method: external merge  Disk: 181240kB"
```

"Not tested" is not a formality. Textbook advice does not always pay off, which
is the reason the tool tests what it can and labels what it cannot.

Indexes get the proof first because an index can be tried without changing the
application or the data. Proving that a rewritten query still returns the same
rows is a much harder problem.

## Two facts the design rests on

Both checked against the Postgres 18 and HypoPG documentation, and both
reproduced in the lab.

**1. The query list has the values removed.**

The application runs `... WHERE customer_id = 42` and `... WHERE customer_id = 97`.
`pg_stat_statements` folds them into one row reading `... WHERE customer_id = $1`.
The 42 and the 97 are gone.

Postgres cannot time a query with a `$1` in it (`ERROR: there is no parameter $1`),
so the tool has to put a value back first. Which value matters: in the lab,
customer 4242 has 51 orders and customer 1 has 43,405, and the best index for
one is not the best for the other.

**2. A pretend index can be planned with, but not run.**

- `EXPLAIN` asks Postgres how it *would* run a query. Nothing executes.
- `EXPLAIN ANALYZE` runs the query and reports real milliseconds.
- A HypoPG index is pretend: no data on disk. Postgres can plan with it but has
  nothing to read, so it only appears in plain `EXPLAIN`.

So there are two kinds of evidence, and every number in the report is labelled
as one or the other:

- **Estimated** — pretend index, on the live database. "Postgres would use it;
  cost guess 41,668 -> 82."
- **Measured** — real index, on a throwaway copy. "41 ms -> 0.07 ms."

An estimate can be wrong, including about whether a change helps at all. The
lab contains a case where it is.

## Pipeline

```
collect  ->  pick  ->  put values back  ->  explain  ->  read the plan  ->  suggest  ->  test  ->  report
(M2)         (M2)      (M3)                 (M3)         (M4)               (M5, M6)     (M5, M7)  (M8)
```

## Milestones

Each ends with something that runs.

**M0 — Lab.** A Postgres with made-up data and planted slow queries. See
`docs/LAB.md`.

**M1 — Do it all by hand.** No code. `lab/TOUR.md`.
- Concepts: reading every row vs using an index; a guess vs what happened; what an index costs.

**M2 — Collect and rank.** `querysmith top` prints the worst queries.
- Concepts: `pg_stat_statements`; total time vs time per call.
- Design question: what does "worst" mean?
  - Decided 2026-10-17: ranked by total time, each query shown with its share of
    all database time. Time per call and calls are always shown beside it. A
    query that is very fast but called far more than anything else is flagged
    as a possible N+1 rather than ranked on its time.
  - The flag, decided 2026-10-19: under 1 ms a run on average, and called at
    least 10 times as often as the median query listed. Measured against the
    median, one other busy query cannot hide it. In the lab the
    products-by-id query is called hundreds of times as often as the median.
- Design question: which rows are listed?
  - Decided 2026-10-18: every row except those run by the tool's own login,
    each shown with the login that ran it. `--user <login>` lists only that
    login's queries. Administrator queries are not hidden automatically.
  - Only queries run in the database the tool is connected to.
    `pg_stat_statements` covers the whole server, and a query from another
    database cannot be explained in this one.
  - A query's share is out of all the time listed before `--user` narrows the
    list, so it reads the same either way.
- Design question: what if the login can do more than read?
  - Decided 2026-10-18: the tool refuses to run, names what the login could
    change, and prints the SQL that creates a read-only login.
- Must not happen: a login that can do more than read; the tool listing its own queries.

**M3 — Safe explain.** A real, timed plan for one top query.
- Concepts: read-only transactions; timeouts; why the value put back for `$1` changes the plan.
- Design question: which value goes back in? Which statements may be run at all?
- Must not happen: the tool changes data, or runs a ten-minute query on a live database.

**M4 — Read the plan.** The plan as a tree in Java, and what went wrong in it.
- Concepts: a plan is a tree of steps; a step can repeat thousands of times; a badly guessed row count.
- Design question: how is a step modelled? How much time is each step itself responsible for?
- Must not happen: a step that ran 300,000 times reported as taking 0.000 ms.

**M5 — Test an index, estimated.** `querysmith try "CREATE INDEX ..."`.
- Concepts: indexes on more than one column and why order matters; pretend indexes.
- Design question: what would convince a sceptical reviewer the index is worth adding, or not?
- Must not happen: recommending an index Postgres never uses; fixing one query while another gets worse.

**M6 — Explain and suggest.** An LLM gets the findings from M4 and returns an
explanation plus index ideas, which go straight into M5.
- Concepts: treating model output as untrusted input.
- Design question: what does the model see, and what is it allowed to cause?
- Must not happen: a made-up column; model output run as SQL; wording that contradicts the numbers.

**M7 — Test an index, measured.** A real index on a throwaway copy, timed.
- Concepts: caching; why one timing run proves nothing.
- Design question: what is a fair before and after?
- Must not happen: comparing a cold first run with a warm second one; reporting the gain without the price.

**M8 — Ship.** Report file, README, recorded demo, and one run against a real
database.

## Features

**Core**
- Every number labelled estimated or measured.
- Putting values back into queries.
- Regression check: every top query is re-planned with the new index, and every change is reported.
- Index size and write cost next to the gain.
- Rejected suggestions shown, not hidden.
- An answer key for the lab, so the tool's output can be marked right or wrong.

**Next, in this order**
1. A small rule-based index generator, scored against the LLM on the lab queries.
2. Unused and duplicate index finder, so the tool can also recommend removals.
3. A before and after for non-index fixes.
4. "Worst in the last hour" instead of "worst since the counters were last reset".
5. Reading plans pasted as text from logs.

**Cut**
- Web dashboard, applying indexes automatically, a monitoring daemon, databases other than Postgres.

## Decisions and their reasons

| Decision | Reason |
|---|---|
| Command-line tool that prints a report | Smallest thing that shows the database work. Nearly all the effort goes into Postgres, not a UI. |
| Plain Java, no Spring Boot | The tool runs, prints and exits, so there is no server to manage. |
| One explicit database connection, no pool | The safety settings (read-only, timeouts) are set on the connection by hand. And a pretend index exists only inside the connection that created it, so a pool handing out a different connection per call would silently break the tester. |
| pgjdbc, picocli, Jackson, JUnit 6 + Testcontainers, Maven | Driver, CLI parsing, plan JSON, tests against a real Postgres, and Maven through its wrapper, so Maven itself needs no install. |
| The LLM arrives at M6 | Everything before it is plain code that can be tested, and the tester exists before there is any untrusted suggestion to test. |
| Diagnose anything, prove indexes first | An index can be tried without touching the application or the data. |
| `top` ranks by total time, not time per call | Total time is the share of the database's work a query uses, so fixing the top one frees the most. In the lab, a 10x fix to the top query by total saves 79 s per minute of traffic; the same fix to the top query by time per call saves 39 s. Ranking by time per call also puts rare reports first: a 30-second monthly report would top the list forever. Time per call is still shown, so a slow query is visible even when it is not first. |
| `top` leaves out its own login's queries, and nobody else's unless asked | Every row in `pg_stat_statements` records the login that ran it, and the tool always knows its own (`current_user`). Listing only the application's login would need its name, which the tool cannot know on a database it has never seen. Its own test runs must not appear: from M3 it times slow queries itself, and those runs would rank as slow queries. Administrator queries stay, labelled with their login, because many applications connect as the administrator login and hiding it would hide the application. `--user` narrows the list when the application's login is known. |
| Refuse a login that can change data, rather than warn | A read-only login is the safety net: if the tool has a bug and runs an `UPDATE`, Postgres refuses it. It also keeps the tool's login separate from the application's, which the rule above depends on. A warning fixes neither; it only reports them. |
| Lab on Postgres 18 | Current major version; HypoPG is packaged for it. |

## How it is checked

- **Each milestone:** its outcome is shown running in the terminal against the lab.
- **M3:** an `UPDATE`, a `SELECT pg_sleep(600)` and a `SELECT ... FOR UPDATE` are planted in the query list. No rows change, the sleep is cut off, each refusal is reported.
- **M4:** saved plans as test fixtures with hand-worked expected values, including a repeated step, a parallel step and a step that never ran.
- **M5, M7:** for every planted problem the tool's verdict matches `lab/ANSWERS.md`, and at least one planted bad index is rejected.
- **M7:** the same benchmark run twice; the reported spread covers the difference between the two runs.
