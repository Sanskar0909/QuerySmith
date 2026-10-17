-- Runs once, as the postgres superuser, inside the "shop" database.

-- Records every query the server runs: how often, how long, how many rows.
-- This is the list QuerySmith reads to find the worst queries.
CREATE EXTENSION pg_stat_statements;

-- Pretend indexes: lets you ask "would Postgres use this index?" without
-- building it.
CREATE EXTENSION hypopg;
