package com.querysmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.querysmith.Values.Pick;
import com.querysmith.Values.Picked;

/**
 * M3: Values, what goes back in for a $1. Three tables, made before the tests
 * start, each shaped like a case in the lab:
 *
 * <pre>
 *   shop_orders       10,000 rows, statistics fresh
 *     customer_id     customer 1 has 2,000 rows, customer 2 has 1,000, and
 *                     customers 3 to 7002 have one each
 *     status          delivered 9,000, shipped 800, refunded 200
 *     id              1 to 10,000, every one different
 *     note            nothing in it at all
 *
 *   counted           1,000 rows, for the 50 + 10% rule: 150 rows may change
 *
 *   SupportTickets    2,000 tickets, all open, when its statistics were taken.
 *                     Since then 200,000 more have come in, nearly all of
 *                     them closed, so the statistics are out of date, as for
 *                     support_tickets in the lab
 *     status          closed 198,000, open 4,000
 *     customer_id     customer 1 has every 10th ticket; 900 customers from
 *                     1002 to 2000 share the rest, about 200 each
 *     queue           support, for every ticket
 *     id              1 to 202,000, every one different
 *     closed_note     nothing in it at all
 * </pre>
 *
 * Auto-analyze is off for all three, as for support_tickets in the lab, so
 * the statistics stay exactly as each test expects.
 */
class ValuesTest {

    @BeforeAll
    static void makeTables() throws SQLException {
        String[] statements = {
            "CREATE TABLE shop_orders (id int PRIMARY KEY, customer_id int, status text, note text)"
                    + " WITH (autovacuum_enabled = false)",
            """
            INSERT INTO shop_orders
            SELECT g,
                   CASE WHEN g <= 2000 THEN 1 WHEN g <= 3000 THEN 2 ELSE g - 2998 END,
                   CASE WHEN g % 50 = 0 THEN 'refunded' WHEN g % 10 = 0 THEN 'shipped' ELSE 'delivered' END,
                   NULL
            FROM generate_series(1, 10000) g""",

            "CREATE TABLE counted (id int, n int) WITH (autovacuum_enabled = false)",
            "INSERT INTO counted SELECT g, g FROM generate_series(1, 1000) g",

            "CREATE TABLE \"SupportTickets\" (id int, customer_id int, status text, queue text, closed_note text)"
                    + " WITH (autovacuum_enabled = false)",
            // g * 37 % 1000 jumps about, so every page holds customers from
            // all over the range and any sample of pages has the same middle.
            """
            INSERT INTO "SupportTickets"
            SELECT g, CASE WHEN g % 10 = 0 THEN 1 ELSE 1001 + g * 37 % 1000 END, 'open', 'support', NULL
            FROM generate_series(1, 2000) g""",

            // A session's counts of changed rows reach pg_stat_user_tables
            // in batches, about once a second. Counts still waiting when
            // ANALYZE runs are added after it, and the table looks changed
            // since: 10,000 rows changed instead of none.
            "SELECT pg_stat_force_next_flush()",
            "ANALYZE shop_orders, counted, \"SupportTickets\"",

            // One in 100 of the new tickets is still open, spread through the
            // table, so every sample sees some open ones. In the lab the open
            // tickets sit together on the first pages, and a sample can miss
            // them all.
            """
            INSERT INTO "SupportTickets"
            SELECT g, CASE WHEN g % 10 = 0 THEN 1 ELSE 1001 + g * 37 % 1000 END,
                   CASE WHEN g % 100 = 0 THEN 'open' ELSE 'closed' END, 'support', NULL
            FROM generate_series(2001, 202000) g""",
            "SELECT pg_stat_force_next_flush()",

            "GRANT SELECT ON shop_orders, counted, \"SupportTickets\" TO tool",
        };
        try (Connection admin = TestDatabase.connect("postgres")) {
            for (String sql : statements) {
                TestDatabase.execute(admin, sql);
            }
        }
    }

    @Test
    void freshRightAfterAnalyze() throws SQLException {
        assertTrue(fresh("shop_orders"));
    }

    // 50 rows plus 10% of 1,000 is 150.
    @Test
    void freshUntilMoreThan50Plus10PercentHaveChanged() throws SQLException {
        try (Connection admin = TestDatabase.connect("postgres")) {
            TestDatabase.execute(admin, "UPDATE counted SET n = n + 1 WHERE id <= 150");
            flushStats(admin);
            assertTrue(fresh("counted"), "150 rows changed");

            TestDatabase.execute(admin, "UPDATE counted SET n = n + 1 WHERE id = 151");
            flushStats(admin);
            assertFalse(fresh("counted"), "151 rows changed");
        }
    }

    @Test
    void staleOnceManyRowsChangedSinceAnalyze() throws SQLException {
        assertFalse(fresh("SupportTickets"));
    }

    // customer_id: customers 1 and 2 on the commonest list, everyone else in
    // the histogram. 3502 is in the middle of 3 to 7002.
    @Test
    void listAndHistogramGiveTheCommonestAndATypicalValue() throws SQLException {
        Picked picked = pick("shop_orders", "customer_id");

        assertEquals(List.of(new Pick("1", Values.Kind.COMMONEST), new Pick("3502", Values.Kind.TYPICAL)),
                picked.picks());
        assertFalse(picked.sampled());
    }

    // status: only three values, all of them on the commonest list.
    @Test
    void listOnlyGivesBothEnds() throws SQLException {
        assertEquals(List.of(new Pick("delivered", Values.Kind.COMMONEST), new Pick("refunded", Values.Kind.LEAST_COMMON)),
                pick("shop_orders", "status").picks());
    }

    // id: every value different, so none is commoner than the rest.
    @Test
    void histogramOnlyGivesOneTypicalValue() throws SQLException {
        assertEquals(List.of(new Pick("5000", Values.Kind.TYPICAL)), pick("shop_orders", "id").picks());
    }

    // Comparing with NULL matches nothing, so NULL is never a value to time.
    @Test
    void columnWithNothingInItGetsNothing() throws SQLException {
        assertEquals(List.of(), pick("shop_orders", "note").picks());
        assertEquals(List.of(), pick("SupportTickets", "closed_note").picks());
    }

    // pg_stats still says every ticket is open, as in the lab. 198,000 of
    // the 202,000 are closed. Two values seen, so both ends, as for a
    // commonest list only.
    @Test
    void staleStatisticsAreNotTrusted() throws SQLException {
        Picked picked = pick("SupportTickets", "status");

        assertTrue(picked.sampled());
        assertEquals(List.of(new Pick("closed", Values.Kind.COMMONEST), new Pick("open", Values.Kind.LEAST_COMMON)),
                picked.picks());
    }

    // Both ends are the same value, so it is timed once.
    @Test
    void sampleWithOneValueGivesJustThatOne() throws SQLException {
        assertEquals(List.of(new Pick("support", Values.Kind.COMMONEST)), pick("SupportTickets", "queue").picks());
    }

    // Over 100 values, as for a commonest list and a histogram. The middle is
    // a row's value, not a ticket count: 10% of rows are customer 1, the
    // rest spread over 1002 to 2000, so the middle row is near 1445.
    @Test
    void sampleWithManyValuesGivesTheCommonestAndTheMiddle() throws SQLException {
        List<Pick> picks = pick("SupportTickets", "customer_id").picks();

        assertEquals(2, picks.size(), picks.toString());
        assertEquals(new Pick("1", Values.Kind.COMMONEST), picks.get(0));
        assertEquals(Values.Kind.TYPICAL, picks.get(1).kind());
        assertBetween(1_400, 1_600, picks.get(1).value());
    }

    // Every value seen once, so none is commoner than the rest, as for a
    // histogram only. The middle of 1 to 202,000 is about 101,000; a sample
    // lands within a few thousand of it. Sorted as text instead of as
    // numbers, "190000" comes before "2" and the middle is far off.
    @Test
    void sampleWithEveryValueOnceGivesTheMiddleOnly() throws SQLException {
        List<Pick> picks = pick("SupportTickets", "id").picks();

        assertEquals(1, picks.size(), picks.toString());
        assertEquals(Values.Kind.TYPICAL, picks.get(0).kind());
        assertBetween(50_000, 150_000, picks.get(0).value());
    }

    // The pages are picked at random, so the rows read vary by a few
    // thousand. Reading the whole table, or counting it first, reads 202,000.
    // So does a sample sized from the table as it was when its statistics
    // were taken: 30,000 rows out of 2,000 is all of them.
    @Test
    void sampleReadsAbout30000RowsNotTheWholeTable() throws SQLException {
        long before = rowsReadFrom("SupportTickets");
        try (Connection tool = TestDatabase.connect("tool")) {
            Values.pick(tool, "public", "SupportTickets", "status");
            flushStats(tool);
        }
        long read = rowsReadFrom("SupportTickets") - before;

        assertTrue(read > 15_000 && read < 60_000, "read " + read + " of 202,000 rows");
    }

    // q01 in the lab: 20 rows per call, but some customers have fewer than
    // 20 orders, so it averages 19.23.
    @Test
    void limitIsRowsPerCallRoundedUp() {
        assertEquals(OptionalLong.of(20), Values.limit(stat(100, 1_923)));
        assertEquals(OptionalLong.of(10), Values.limit(stat(50, 500)));
    }

    @Test
    void noRowsNoLimit() {
        assertEquals(OptionalLong.empty(), Values.limit(stat(3, 0)));
        // A query that failed every time is recorded with no finished calls.
        assertEquals(OptionalLong.empty(), Values.limit(stat(0, 0)));
    }

    @Test
    void namesAreQuotedToBeReadExactly() {
        assertEquals("\"orders\"", Values.quoted("orders"));
        assertEquals("\"SupportTickets\"", Values.quoted("SupportTickets"));
        // A quote inside a name is written twice.
        assertEquals("\"odd\"\"name\"", Values.quoted("odd\"name"));
    }

    private static boolean fresh(String table) throws SQLException {
        try (Connection tool = TestDatabase.connect("tool")) {
            return Values.fresh(tool, "public", table);
        }
    }

    private static Picked pick(String table, String column) throws SQLException {
        try (Connection tool = TestDatabase.connect("tool")) {
            return Values.pick(tool, "public", table, column);
        }
    }

    private static void assertBetween(long low, long high, String value) {
        long n = Long.parseLong(value);
        assertTrue(n >= low && n <= high, value + " is not between " + low + " and " + high);
    }

    /** Sends this session's waiting counts to pg_stat_user_tables now. */
    private static void flushStats(Connection conn) throws SQLException {
        TestDatabase.execute(conn, "SELECT pg_stat_force_next_flush()");
    }

    private static long rowsReadFrom(String table) throws SQLException {
        try (Connection admin = TestDatabase.connect("postgres");
             Statement st = admin.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT seq_tup_read FROM pg_stat_user_tables WHERE relname = '" + table + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static QueryStat stat(long calls, long rows) {
        return new QueryStat("app", calls, rows, 0, 0, 0, "SELECT id FROM orders LIMIT $1");
    }
}
