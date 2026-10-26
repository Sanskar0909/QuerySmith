package com.querysmith;

import java.sql.*;
import java.time.Duration;
import java.util.List;

/**
 * One timed run of a query on a live database, made safe (docs/DESIGN.md, M3).
 *
 * The tool's login is already read-only (M2). Each run adds three more
 * guards, all ending with the run:
 * <ul>
 *   <li>a read-only transaction, which refuses anything that changes data,
 *       however it is written: an UPDATE inside a WITH, nextval(), row locks;</li>
 *   <li>a time limit, so a ten-minute query is stopped;</li>
 *   <li>a lock limit, so the run never waits in line behind another
 *       session's lock.</li>
 * </ul>
 *
 * Tested by SafeRunTest.
 */
final class SafeRun {

    /** How long a run may wait for another session's lock before giving up. */
    static final Duration LOCK_WAIT = Duration.ofSeconds(1);

    private SafeRun() {}

    /** What one run gave back. */
    sealed interface Outcome {}

    /** The plan as EXPLAIN ANALYZE printed it: its rows joined with "\n". */
    record Plan(String text) implements Outcome {}

    /** Stopped by the time limit. Postgres's error code 57014. */
    record TimedOut() implements Outcome {}

    /** Gave up waiting for another session's lock. Error code 55P03. */
    record Locked() implements Outcome {}

    /**
     * Refused by Postgres: a change inside a read-only transaction (error
     * code 25006), or something the login has no permission for (42501).
     *
     * @param reason Postgres's own message, for the report
     */
    record Refused(String reason) implements Outcome {}

    /**
     * Runs {@code EXPLAIN (ANALYZE, BUFFERS)} on the query, safely.
     *
     * <ol>
     *   <li>Starts a transaction and makes it read-only.</li>
     *   <li>Sets {@code statement_timeout} to {@code timeout} and
     *       {@code lock_timeout} to {@link #LOCK_WAIT}, for this transaction
     *       only.</li>
     *   <li>Runs {@code EXPLAIN (ANALYZE, BUFFERS) } followed by the query.
     *       Each {@code ?} gets the value at the same position in
     *       {@code values}, handed over as a placeholder and never pasted into
     *       the SQL. No type is given with it, so Postgres takes the type from
     *       whatever the value is compared with: "1" becomes a number when it
     *       is compared with an int column.</li>
     *   <li>Always rolls back, whatever happened, and leaves the connection as
     *       it found it: auto-commit as before, no limits left behind.</li>
     * </ol>
     *
     * The errors listed on the outcomes above come back as those outcomes.
     * Any other error is thrown.
     *
     * @param query  the query with a {@code ?} wherever pg_stat_statements
     *               wrote {@code $1}, {@code $2}, and so on (ParamFinder does
     *               that)
     * @param values one per {@code ?}, in the order the {@code ?}s appear,
     *               as text
     */
    static Outcome explainAnalyze(Connection conn, String query, List<String> values, Duration timeout)
            throws SQLException {

        boolean autoCommitInitStatus = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try(Statement st = conn.createStatement()) {
                st.execute("SET TRANSACTION READ ONLY");
                st.execute("SET LOCAL lock_timeout = " + LOCK_WAIT.toMillis());
                st.execute("SET LOCAL statement_timeout = " + timeout.toMillis());
            }

            String SQLQuery = "EXPLAIN (ANALYZE, BUFFERS) " + query;

            try (PreparedStatement ps = conn.prepareStatement(SQLQuery)) {
                for(int i = 0; i < values.size(); i++) {
                    ps.setObject(i+1, values.get(i), Types.OTHER);
                }

                StringBuilder sb = new StringBuilder();
                try(ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if(!sb.isEmpty())
                            sb.append("\n");

                        sb.append(rs.getString(1));
                    }
                }

                return new Plan(sb.toString());
            }


        } catch (SQLException e) {
            return switch (String.valueOf(e.getSQLState())) {
                case "57014" -> new TimedOut();
                case "55P03" -> new Locked();
                case "25006", "42501" -> new Refused(e.getMessage());
                default -> throw e;
            };
        }
        finally {
            conn.rollback();
            if(autoCommitInitStatus)
                conn.setAutoCommit(true);
        }
    }
}
