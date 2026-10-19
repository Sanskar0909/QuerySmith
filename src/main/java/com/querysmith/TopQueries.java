package com.querysmith;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The queries costing the database the most, read from pg_stat_statements.
 *
 * The rules, decided for M2 (docs/DESIGN.md):
 * <ul>
 *   <li>Worst first, by total time: all of a query's runs added up.</li>
 *   <li>Only queries run in the database this connection is connected to.
 *       pg_stat_statements covers every database on the server.</li>
 *   <li>Never the queries of the login this connection uses. That login is
 *       the tool's own.</li>
 *   <li>{@code onlyLogin == null} lists every other login; otherwise only
 *       that one.</li>
 *   <li>{@code sharePercent} is out of all the time in the list before
 *       {@code onlyLogin} narrows it, so a query's share is the same with or
 *       without the filter.</li>
 * </ul>
 *
 * Tested by TopQueriesTest.
 */
final class TopQueries {

    private TopQueries() {}

    /** At most {@code limit} rows, worst first. */
    static List<QueryStat> top(Connection conn, int limit, String onlyLogin) throws SQLException {
        String sqlQuery = """
                WITH listed AS (
                    SELECT pg_get_userbyid(userid) AS login,
                           calls,
                           total_exec_time,
                           total_exec_time AS total_ms,
                           mean_exec_time AS mean_ms,
                           (100 * total_exec_time / sum(total_exec_time) OVER ()) AS share_pct,
                           query AS query
                    FROM pg_stat_statements
                    WHERE userid <> (SELECT oid FROM pg_roles WHERE rolname = current_user)
                      AND dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                )
                SELECT login, calls, total_ms, mean_ms, share_pct, query
                FROM listed
                WHERE ? IS NULL OR login = ?
                ORDER BY total_exec_time DESC
                LIMIT ?;
                """;
        List<QueryStat> queryStats = new ArrayList<>();

        try(PreparedStatement ps = conn.prepareStatement(sqlQuery)) {
            ps.setString(1, onlyLogin);
            ps.setString(2, onlyLogin);
            ps.setInt(3, limit);
            try(ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String login = rs.getString(1);
                    long calls = rs.getLong(2);
                    double totalMs = rs.getDouble(3);
                    double meanMs = rs.getDouble(4);
                    double sharePct = rs.getDouble(5);
                    String query = rs.getString(6);

                    QueryStat qs = new QueryStat(login, calls, totalMs, meanMs, sharePct, query);
                    queryStats.add(qs);
                }

            }
        }

        return queryStats;
    }
}
