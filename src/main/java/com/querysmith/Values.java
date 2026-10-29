package com.querysmith;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * The values put back in place of a $1, read from what Postgres knows about
 * the column the $1 is compared with (docs/DESIGN.md, M3).
 *
 * A value is never made up. When nothing is known about the column, nothing
 * is picked, and the query gets its plan without values instead.
 *
 * Tested by ValuesTest.
 */
final class Values {

    /**
     * Rows read when the statistics are out of date: as many as ANALYZE itself
     * reads, so a huge table does not mean a huge read.
     */
    static final int SAMPLE_ROWS = 30_000;

    /**
     * A sample with at most this many different values is treated like a
     * commonest list only. Postgres's own commonest list holds up to 100.
     */
    static final int FEW_VALUES = 100;

    private Values() {}

    /** Why a value was picked. */
    enum Kind {
        /** The value with the most rows. The slow case for an {@code =}. */
        COMMONEST,
        /** One of the many ordinary values. What most runs see. */
        TYPICAL,
        /**
         * The last value on the commonest list, when every value the column
         * holds is on it. The other end from COMMONEST.
         */
        LEAST_COMMON
    }

    /** One value to time the query with, as text. */
    record Pick(String value, Kind kind) {}

    /**
     * @param picks   COMMONEST first when there is one; empty when nothing is
     *                known about the column
     * @param sampled true when the statistics were out of date and the values
     *                come from a sample of the table instead. The report then
     *                also says the statistics are out of date.
     */
    record Picked(List<Pick> picks, boolean sampled) {}

    /**
     * True while the table's statistics are fresh by Postgres's own rule for
     * refreshing them: until more than 50 rows plus 10% of the table have been
     * inserted, updated or deleted since they were last taken. Those two
     * numbers are Postgres's defaults; a server can change them.
     *
     * Names here and below are as they were created, without quotes:
     * "public", "SupportTickets".
     */
    static boolean fresh(Connection conn, String schema, String table) throws SQLException {
        String query = """
                SELECT a.n_mod_since_analyze FROM pg_stat_user_tables a JOIN pg_class b ON a.relid = b.oid
                WHERE a.schemaname = ? AND a.relname = ? AND a.n_mod_since_analyze <= 50 + 0.1 * b.reltuples
                """;
        try(PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, schema);
            ps.setString(2, table);

            try(ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }

        }
    }

    /**
     * The values for one column.
     *
     * <p>While the table's statistics are fresh, from {@code pg_stats}, by
     * what it holds for the column:
     * <ul>
     *   <li>a commonest list and a histogram: the first entry of the list
     *       (COMMONEST) and the middle entry of the histogram (TYPICAL);</li>
     *   <li>a commonest list only: its first entry (COMMONEST) and its last
     *       (LEAST_COMMON);</li>
     *   <li>a histogram only: its middle entry (TYPICAL);</li>
     *   <li>neither: nothing.</li>
     * </ul>
     *
     * <p>When they are out of date, from a sample of about
     * {@link #SAMPLE_ROWS} rows of the table, taken a page at a time
     * ({@code TABLESAMPLE SYSTEM}), NULLs left out. The same shapes, judged
     * from what the sample saw:
     * <ul>
     *   <li>at most {@link #FEW_VALUES} different values: the most seen
     *       (COMMONEST) and the least seen (LEAST_COMMON), or just the one
     *       if only one was seen;</li>
     *   <li>more, and some value seen more than once: the most seen
     *       (COMMONEST) and the middle row's value (TYPICAL);</li>
     *   <li>every value seen once: the middle row's value (TYPICAL);</li>
     *   <li>no rows: nothing.</li>
     * </ul>
     * The middle row is the one halfway down the sample sorted by the
     * column, numbers sorted as numbers. All of these come from the same
     * sample: a second sample would see different rows.
     */
    static Picked pick(Connection conn, String schema, String table, String column) throws SQLException {
        if (fresh(conn, schema, table)) {
            return new Picked(fromStats(conn, schema, table, column), false);
        }
        return new Picked(fromSample(conn, schema, table, column), true);
    }

    private static List<Pick> fromStats(Connection conn, String schema, String table, String column)
            throws SQLException {

        String query = """
                SELECT histogram_bounds::text::text[], most_common_vals::text::text[] FROM pg_stats WHERE schemaname=? AND tablename=? AND attname=?
                """;

        List<Pick> result = new ArrayList<>();
        try(PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);

            try(ResultSet rs = ps.executeQuery()) {
                boolean next = rs.next();
                if(!next) {
                    return new ArrayList<>();
                }
                Array histogramArray = rs.getArray(1);
                Array commonArray = rs.getArray(2);
                String[] histoArr = histogramArray == null ? new String[0] : (String[]) histogramArray.getArray();
                String[] commonArr = commonArray == null ? new String[0] : (String[]) commonArray.getArray();

                Pick common;
                Pick typical;
                Pick leastCommon;
                if(histoArr.length > 0 && commonArr.length > 0) {
                    common = new Pick(commonArr[0], Kind.COMMONEST);
                    typical = new Pick(histoArr[histoArr.length / 2], Kind.TYPICAL);
                    result.add(common);
                    result.add(typical);
                }
                else if(commonArr.length > 0) {
                    common = new Pick(commonArr[0], Kind.COMMONEST);
                    leastCommon = new Pick(commonArr[commonArr.length - 1], Kind.LEAST_COMMON);
                    result.add(common);
                    result.add(leastCommon);
                }
                else if(histoArr.length > 0){
                    typical = new Pick(histoArr[histoArr.length / 2], Kind.TYPICAL);
                    result.add(typical);
                }
            }
        }

        return result;
    }

    private static List<Pick> fromSample(Connection conn, String schema, String table, String column)
            throws SQLException {
        String query = """
                SELECT n_live_tup FROM pg_stat_user_tables WHERE schemaname = ? AND relname = ?
                """;

        try(PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, schema);
            ps.setString(2, table);

            try(ResultSet rs = ps.executeQuery()) {
                rs.next();
                long rows = rs.getLong(1);
                double percent = Math.min(100, 100.0 * SAMPLE_ROWS / rows);
                String statQuery = """
                        WITH s AS MATERIALIZED (
                            SELECT %s AS v FROM %s TABLESAMPLE SYSTEM (?)
                            WHERE %s IS NOT NULL
                        ), counted AS (
                            SELECT v, count(*) AS n FROM s GROUP BY v
                        )
                        SELECT (SELECT count(*) FROM counted) AS different,
                        (SELECT MAX(n) FROM counted LIMIT 1) AS most_n,
                        (SELECT v::text FROM counted ORDER BY n DESC LIMIT 1) AS most,
                        (SELECT v::text FROM counted ORDER BY n LIMIT 1) AS least,
                        (SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY v) FROM s)::text AS middle;
                        """.formatted(quoted(column), quoted(schema)+"."+quoted(table), quoted(column));

                try(PreparedStatement statStatement = conn.prepareStatement(statQuery)) {
                    statStatement.setDouble(1, percent);
                    try(ResultSet statResult = statStatement.executeQuery()) {
                        statResult.next();
                        long different = statResult.getLong(1);
                        long mostN = statResult.getLong(2);

                        if (different == 0)
                            return new ArrayList<>();

                        if(different <= FEW_VALUES) {
                            List<Pick> result = new ArrayList<>();
                            Pick commonPick = new Pick(statResult.getString(3), Kind.COMMONEST);
                            result.add(commonPick);

                            if(different > 1)
                                result.add(new Pick(statResult.getString(4), Kind.LEAST_COMMON));

                            return result;
                        }

                        else if(mostN > 1) {
                            List<Pick> result = new ArrayList<>();
                            Pick commonPick = new Pick(statResult.getString(3), Kind.COMMONEST);
                            result.add(commonPick);
                            result.add(new Pick(statResult.getString(5), Kind.TYPICAL));

                            return result;
                        }

                        else {
                            List<Pick> result = new ArrayList<>();
                            Pick middlePick = new Pick(statResult.getString(5), Kind.TYPICAL);
                            result.add(middlePick);

                            return result;
                        }

                    }
                }
            }
        }
    }


    /**
     * The value for a LIMIT at the end of the query: the rows it returned per
     * call, rounded up. An application usually writes its LIMIT as a fixed
     * number, and every call that finds enough rows returns exactly that many.
     *
     * Empty when it never returned a row, or never finished a run: there is
     * nothing to go on.
     */
    static OptionalLong limit(QueryStat stat) {
        if (stat.calls() == 0 || stat.rows() == 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.ceilDiv(stat.rows(), stat.calls()));
    }

    /**
     * A table or column name written so that Postgres reads it exactly as it
     * is: {@code SupportTickets} becomes {@code "SupportTickets"}. Unquoted,
     * Postgres would read it as supporttickets.
     *
     * Names cannot go in as {@code ?} placeholders, which only take values, so
     * a name has to be written into the SQL itself, and this is how.
     */
    static String quoted(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
