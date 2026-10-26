package com.querysmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.querysmith.SafeRun.Locked;
import com.querysmith.SafeRun.Outcome;
import com.querysmith.SafeRun.Plan;
import com.querysmith.SafeRun.Refused;
import com.querysmith.SafeRun.TimedOut;

/**
 * M3: SafeRun, one run made safe. See TestDatabase for the logins.
 *
 * Several tests run as "app", which may change products. Only the read-only
 * transaction stands between those statements and the data, so each one
 * checks afterwards that nothing changed.
 */
class SafeRunTest {

    static final Duration LIMIT = Duration.ofSeconds(5);

    @Test
    void readComesBackWithItsTimedPlan() throws SQLException {
        Plan plan = assertInstanceOf(Plan.class, run("tool", "SELECT name FROM products WHERE id = ?", "1"));

        // "actual" and "Execution Time" are only there when the query really ran.
        assertTrue(plan.text().contains("actual"), plan.text());
        assertTrue(plan.text().contains("Execution Time"), plan.text());
        // The value is in the plan, so it reached Postgres.
        assertTrue(plan.text().contains("(id = 1)"), plan.text());
    }

    // Pasted into the SQL, this value would end the string and start a DROP.
    // Handed over as a placeholder it is only ever a value: one long name.
    @Test
    void valuesGoInAsPlaceholdersNeverAsSql() throws SQLException {
        Outcome out = run("tool", "SELECT id FROM products WHERE name = ? LIMIT ?",
                "it's'); DROP TABLE products; --", "20");

        assertInstanceOf(Plan.class, out);
        assertEquals(100, count("SELECT count(*) FROM products"));
    }

    @Test
    void changeIsRefusedEvenForALoginThatMayChangeData() throws SQLException {
        Refused refused = assertInstanceOf(Refused.class,
                run("app", "UPDATE products SET name = 'changed' WHERE id = ?", "1"));

        assertTrue(refused.reason().contains("read-only transaction"), refused.reason());
        assertNothingChanged();
    }

    // Experiment 6, statement 5. It starts like a read and gets past
    // StatementCheck; the transaction is what stops it.
    @Test
    void changeHiddenInsideWithIsRefused() throws SQLException {
        assertInstanceOf(Refused.class, run("app",
                "WITH changed AS (UPDATE products SET name = 'changed' WHERE id = ? RETURNING id) SELECT * FROM changed",
                "1"));
        assertNothingChanged();
    }

    // A row lock is written into the row, and makes the application's own
    // changes to that row wait until the run ends.
    @Test
    void rowLocksAreRefused() throws SQLException {
        assertInstanceOf(Refused.class, run("app", "SELECT id FROM products WHERE id = ? FOR UPDATE", "1"));
        assertInstanceOf(Refused.class, run("app", "SELECT id FROM products WHERE id = ? FOR SHARE", "1"));
    }

    @Test
    void nextvalIsRefused() throws SQLException {
        assertInstanceOf(Refused.class, run("app", "SELECT nextval('ticket_numbers')"));

        // A sequence never goes back, even on ROLLBACK, so this would stay
        // true for good had nextval() run.
        try (Connection admin = TestDatabase.connect("postgres")) {
            assertEquals("f", text(admin, "SELECT is_called FROM ticket_numbers"));
        }
    }

    @Test
    void readOnlyLoginIsRefusedToo() throws SQLException {
        assertInstanceOf(Refused.class, run("tool", "UPDATE products SET name = 'changed' WHERE id = ?", "1"));
        assertNothingChanged();
    }

    @Test
    void longQueryIsStoppedAtTheLimit() throws SQLException {
        long start = System.nanoTime();
        Outcome out;
        try (Connection tool = TestDatabase.connect("tool")) {
            out = SafeRun.explainAnalyze(tool, "SELECT pg_sleep(?)", List.of("600"), Duration.ofSeconds(1));
        }
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertInstanceOf(TimedOut.class, out);
        assertTrue(took.compareTo(Duration.ofSeconds(5)) < 0, "took " + took);
    }

    // A migration is holding the table. The run gives up after LOCK_WAIT,
    // long before its own 5 s limit, and says why.
    @Test
    void lockWaitGivesUpAfterLockWait() throws SQLException {
        try (Connection migration = TestDatabase.connect("app")) {
            migration.setAutoCommit(false);
            TestDatabase.execute(migration, "LOCK TABLE products IN ACCESS EXCLUSIVE MODE");

            long start = System.nanoTime();
            Outcome out = run("tool", "SELECT count(*) FROM products WHERE id = ?", "1");
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            migration.rollback();

            assertInstanceOf(Locked.class, out);
            assertTrue(took.compareTo(SafeRun.LOCK_WAIT) >= 0, "took " + took);
            assertTrue(took.compareTo(Duration.ofSeconds(4)) < 0, "took " + took);
        }
    }

    // Even a read takes a light lock on the table, kept until its
    // transaction ends. Left open, it would make the next migration wait,
    // and everything queued behind the migration with it.
    @Test
    void runLeavesNoLockBehind() throws SQLException {
        try (Connection tool = TestDatabase.connect("tool");
             Connection migration = TestDatabase.connect("app")) {
            SafeRun.explainAnalyze(tool, "SELECT count(*) FROM products WHERE id = ?", List.of("1"), LIMIT);

            migration.setAutoCommit(false);
            // NOWAIT: fail at once instead of waiting, if anyone holds a lock.
            TestDatabase.execute(migration, "LOCK TABLE products IN ACCESS EXCLUSIVE MODE NOWAIT");
            migration.rollback();
        }
    }

    // Stopped half-way is the hardest case to clean up after.
    @Test
    void connectionIsLeftAsItWasAfterBeingStopped() throws SQLException {
        try (Connection tool = TestDatabase.connect("tool")) {
            SafeRun.explainAnalyze(tool, "SELECT pg_sleep(?)", List.of("600"), Duration.ofSeconds(1));

            assertLeftAsItWas(tool);
        }
    }

    // Auto-commit is the caller's choice. A run puts it back as it found it.
    @Test
    void autoCommitThatWasOffStaysOff() throws SQLException {
        try (Connection tool = TestDatabase.connect("tool")) {
            tool.setAutoCommit(false);
            SafeRun.explainAnalyze(tool, "SELECT 1", List.of(), LIMIT);

            assertFalse(tool.getAutoCommit(), "auto-commit was switched on");
        }
    }

    @Test
    void otherErrorsAreThrown() throws SQLException {
        try (Connection tool = TestDatabase.connect("tool")) {
            assertThrows(SQLException.class,
                    () -> SafeRun.explainAnalyze(tool, "SELEC name FROM products", List.of(), LIMIT));

            assertLeftAsItWas(tool);
        }
    }

    private static void assertLeftAsItWas(Connection conn) throws SQLException {
        assertTrue(conn.getAutoCommit(), "auto-commit is off");
        assertEquals("0", text(conn, "SHOW statement_timeout"));
        assertEquals("0", text(conn, "SHOW lock_timeout"));
        assertEquals("off", text(conn, "SHOW transaction_read_only"));
        assertInstanceOf(Plan.class, SafeRun.explainAnalyze(conn, "SELECT 1", List.of(), LIMIT));
    }

    private static void assertNothingChanged() throws SQLException {
        assertEquals(0, count("SELECT count(*) FROM products WHERE name = 'changed'"));
    }

    private static Outcome run(String login, String query, String... values) throws SQLException {
        try (Connection conn = TestDatabase.connect(login)) {
            return SafeRun.explainAnalyze(conn, query, List.of(values), LIMIT);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection admin = TestDatabase.connect("postgres")) {
            return Long.parseLong(text(admin, sql));
        }
    }

    private static String text(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
