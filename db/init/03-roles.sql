-- Three logins, each allowed to do a different amount.
--
--   postgres       the administrator. Owns everything. You use it in the tour
--                  to create and drop indexes.
--   shop_app       the shop's application. The workload runs as this, so the
--                  slow queries in pg_stat_statements belong to it.
--   querysmith_ro  the tool. Read-only.

CREATE ROLE shop_app LOGIN PASSWORD 'shop_app';
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO shop_app;
-- Inserting a row draws its id from a sequence, which needs its own permission.
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO shop_app;

CREATE ROLE querysmith_ro LOGIN PASSWORD 'querysmith_ro';

-- SELECT only. The tool has to be able to read the tables, because running a
-- query to time it means reading what the query reads. It gets no INSERT,
-- UPDATE or DELETE, so even a bug in the tool cannot change data.
GRANT SELECT ON ALL TABLES IN SCHEMA public TO querysmith_ro;

-- Anyone can read pg_stat_statements, but the text of queries run by OTHER
-- logins shows as "<insufficient privilege>" unless you hold this built-in
-- role. The tool needs to see shop_app's queries, so it needs this.
-- It grants reading statistics and nothing else.
GRANT pg_read_all_stats TO querysmith_ro;

-- Deliberately not set here: a read-only default, a statement timeout, a lock
-- timeout. Where those belong (on the login, on the connection, in the tool's
-- code, or all three) is the design question of milestone M3.
