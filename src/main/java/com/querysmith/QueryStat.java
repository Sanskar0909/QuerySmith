package com.querysmith;

/**
 * One row of the ranking: one kind of query, with all of its runs added up.
 *
 * @param login        the login that ran it, spelled as it was created: "shop_app", or "QuerySmith" without quotes
 * @param calls        how many times it ran
 * @param rows         the rows all of its runs returned, added up; for a write, the rows it changed
 * @param totalMs      all of its runs added up, in milliseconds
 * @param meanMs       one run on average, in milliseconds
 * @param sharePercent its share of all the database time in the list, 0 to 100
 * @param query        the query text as Postgres stored it, with $1, $2 where the values were
 */
record QueryStat(String login, long calls, long rows, double totalMs, double meanMs, double sharePercent,
        String query) {}
