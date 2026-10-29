package com.querysmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 7: TopQueries.top. Every test reads the same small, known workload,
 * played once before the tests start. pg_sleep makes the timings predictable.
 *
 * <pre>
 *   login       database  query                                           runs  time
 *   app         shop      SELECT pg_sleep($1)                                4  4 x 50 ms  = 200 ms   first by total
 *   app         shop      SELECT count(*) FROM pg_sleep($1)                  1  1 x 120 ms = 120 ms   slowest per run
 *   postgres    shop      SELECT count(*) FROM products, pg_sleep($1)        1  1 x 30 ms
 *   app         shop      SELECT name FROM products WHERE id = $1          100  fast each, called the most
 *   app         shop      LONG_QUERY, five lines                             1  fast
 *   app         other     SELECT pg_sleep($1)                                1  300 ms, another database
 *   tool        shop      SELECT count(*) FROM products, pg_sleep($1)        1  80 ms, the tool's own login
 *   QuerySmith  shop      SELECT count(*) FROM products WHERE id <= $1       1  fast, a login with capitals
 * </pre>
 *
 * The rows from "other" and from "tool" would rank first and third if they
 * were listed. Neither may be.
 */
class TopQueriesTest {

    static final String SLEEP = "SELECT pg_sleep($1)";
    static final String COUNT_SLEEP = "SELECT count(*) FROM pg_sleep($1)";
    static final String PRODUCT_BY_ID = "SELECT name FROM products WHERE id = $1";
    static final String FEW_PRODUCTS = "SELECT count(*) FROM products WHERE id <= $1";
    // Over one line and long: what pg_stat_statements stores is kept as it is.
    static final String LONG_QUERY = """
            SELECT id, name
            FROM products
            WHERE id BETWEEN $1 AND $2
              AND name LIKE $3
            ORDER BY name DESC""";

    @BeforeAll
    static void playWorkload() throws SQLException {
        try (Connection admin = TestDatabase.connect("postgres")) {
            // Start from an empty list. This statement itself is recorded too.
            TestDatabase.execute(admin, "SELECT pg_stat_statements_reset()");
        }
        try (Connection app = TestDatabase.connect("app")) {
            for (int i = 0; i < 4; i++) {
                TestDatabase.execute(app, "SELECT pg_sleep(0.05)");
            }
            TestDatabase.execute(app, "SELECT count(*) FROM pg_sleep(0.12)");
            try (PreparedStatement ps = app.prepareStatement("SELECT name FROM products WHERE id = ?")) {
                for (int id = 1; id <= 100; id++) {
                    ps.setInt(1, id);
                    ps.executeQuery().close();
                }
            }
            TestDatabase.execute(app, LONG_QUERY.replace("$1", "1").replace("$2", "50").replace("$3", "'product%'"));
        }
        try (Connection admin = TestDatabase.connect("postgres")) {
            TestDatabase.execute(admin, "SELECT count(*) FROM products, pg_sleep(0.03)");
        }
        try (Connection appInOtherDatabase = TestDatabase.connect("app", "other")) {
            TestDatabase.execute(appInOtherDatabase, "SELECT pg_sleep(0.3)");
        }
        try (Connection tool = TestDatabase.connect("tool")) {
            TestDatabase.execute(tool, "SELECT count(*) FROM products, pg_sleep(0.08)");
        }
        try (Connection capitals = TestDatabase.connect("QuerySmith")) {
            TestDatabase.execute(capitals, "SELECT count(*) FROM products WHERE id <= 10");
        }
    }

    @Test
    void worstByTotalTimeComesFirst() throws SQLException {
        List<QueryStat> rows = topAsTool(10, null);

        assertEquals(SLEEP, rows.get(0).query());
        assertEquals("app", rows.get(0).login());
        assertEquals(COUNT_SLEEP, rows.get(1).query());
    }

    // The decision from the tour's Question 4: one run of 120 ms ranks below
    // four runs of 50 ms, even though it is the slower query each time.
    @Test
    void slowestPerRunIsNotFirst() throws SQLException {
        List<QueryStat> rows = topAsTool(10, null);

        assertTrue(rows.get(1).meanMs() > rows.get(0).meanMs(), rows.toString());
        assertTrue(rows.get(1).totalMs() < rows.get(0).totalMs(), rows.toString());
    }

    @Test
    void callsAndTimesAreReported() throws SQLException {
        QueryStat sleep = find(topAsTool(100, null), SLEEP, "app");

        assertEquals(4, sleep.calls());
        assertTrue(sleep.meanMs() >= 50, "mean " + sleep.meanMs());
        assertTrue(sleep.totalMs() >= 200, "total " + sleep.totalMs());
        assertEquals(sleep.calls() * sleep.meanMs(), sleep.totalMs(), sleep.totalMs() * 0.01);

        assertEquals(100, find(topAsTool(100, null), PRODUCT_BY_ID, "app").calls());
    }

    // M3 takes a LIMIT's value from the rows a query returned per call.
    @Test
    void rowsAreReported() throws SQLException {
        List<QueryStat> rows = topAsTool(100, null);

        // 100 runs, one product each.
        assertEquals(100, find(rows, PRODUCT_BY_ID, "app").rows());
        // One run: ids 1 to 50, and every name starts with "product".
        assertEquals(50, find(rows, LONG_QUERY, "app").rows());
    }

    // Rounding and cutting are for the screen, and TopReport does them. Here
    // the numbers stay exact and the text whole, line breaks included.
    @Test
    void queryTextIsComplete() throws SQLException {
        find(topAsTool(100, null), LONG_QUERY, "app");
    }

    @Test
    void limitCutsTheList() throws SQLException {
        assertEquals(2, topAsTool(2, null).size());
    }

    @Test
    void sharesAddUpToTheWholeList() throws SQLException {
        List<QueryStat> rows = topAsTool(100, null);

        double totalOfAll = rows.stream().mapToDouble(QueryStat::totalMs).sum();
        double sharesAdded = rows.stream().mapToDouble(QueryStat::sharePercent).sum();
        assertEquals(100.0, sharesAdded, 0.01);
        assertEquals(100.0 * rows.get(0).totalMs() / totalOfAll, rows.get(0).sharePercent(), 0.01);
    }

    @Test
    void neverListsItsOwnLogin() throws SQLException {
        for (QueryStat row : topAsTool(100, null)) {
            assertTrue(!row.login().equals("tool"), row.toString());
        }
    }

    @Test
    void listsOtherLoginsByDefault() throws SQLException {
        find(topAsTool(100, null), "SELECT count(*) FROM products, pg_sleep($1)", "postgres");
    }

    // The 300 ms query in the other database would be first if it were counted.
    @Test
    void onlyListsQueriesFromThisDatabase() throws SQLException {
        List<QueryStat> sleeps = topAsTool(100, null).stream()
                .filter(row -> row.query().equals(SLEEP))
                .toList();

        assertEquals(1, sleeps.size(), sleeps.toString());
        assertEquals(4, sleeps.get(0).calls());
    }

    @Test
    void onlyLoginNarrowsTheList() throws SQLException {
        List<QueryStat> rows = topAsTool(100, "app");

        assertTrue(!rows.isEmpty());
        for (QueryStat row : rows) {
            assertEquals("app", row.login(), row.toString());
        }
    }

    @Test
    void shareStaysTheSameWhenNarrowed() throws SQLException {
        double everyone = find(topAsTool(100, null), SLEEP, "app").sharePercent();
        double appOnly = find(topAsTool(100, "app"), SLEEP, "app").sharePercent();

        assertEquals(everyone, appOnly, 0.0001);
    }

    // The tool connected as "QuerySmith". Turning current_user into a login
    // by reading its name as SQL fails here: role "querysmith" does not exist.
    @Test
    void ownLoginWithCapitalLettersIsLeftOut() throws SQLException {
        for (QueryStat row : topAs("QuerySmith", 100, null)) {
            assertTrue(!row.login().equals("QuerySmith"), row.toString());
        }
    }

    // Shown as QuerySmith, not "QuerySmith" with quotes, so that --user takes
    // the name as it was created.
    @Test
    void loginWithCapitalLettersIsShownAsNamed() throws SQLException {
        find(topAsTool(100, null), FEW_PRODUCTS, "QuerySmith");

        List<QueryStat> rows = topAsTool(100, "QuerySmith");
        find(rows, FEW_PRODUCTS, "QuerySmith");
        for (QueryStat row : rows) {
            assertEquals("QuerySmith", row.login(), row.toString());
        }
    }

    private static List<QueryStat> topAsTool(int limit, String onlyLogin) throws SQLException {
        return topAs("tool", limit, onlyLogin);
    }

    private static List<QueryStat> topAs(String login, int limit, String onlyLogin) throws SQLException {
        try (Connection conn = TestDatabase.connect(login)) {
            return TopQueries.top(conn, limit, onlyLogin);
        }
    }

    private static QueryStat find(List<QueryStat> rows, String query, String login) {
        return rows.stream()
                .filter(row -> row.query().equals(query) && row.login().equals(login))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + login + ": " + query + " in " + rows));
    }
}
