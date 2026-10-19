package com.querysmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

/** `querysmith top` against the test database, end to end. */
class TopCommandTest {

    @Test
    void loginThatCanChangeDataIsRefused() throws SQLException {
        Cli.Run run = Cli.run("top", "--db", TestDatabase.url("app"));

        assertEquals(1, run.exitCode());
        assertTrue(run.err().startsWith("querysmith: refusing to run as app"), run.err());
        assertTrue(run.err().contains("  public.products"), run.err());
        assertTrue(run.err().contains("CREATE ROLE querysmith_ro"), run.err());
        // Refused before anything is listed.
        assertTrue(!run.out().contains("per call"), run.out());
    }

    @Test
    void readOnlyLoginGetsTheList() throws SQLException {
        Cli.Run run = Cli.run("top", "--db", TestDatabase.url("tool"), "--top", "3");

        assertEquals(0, run.exitCode(), run.err());
        assertTrue(run.out().startsWith("Connected as tool to shop"), run.out());
        assertEquals("", run.err());
    }

    @Test
    void ownLoginAsUserSaysWhyNothingIsListed() throws SQLException {
        Cli.Run run = Cli.run("top", "--db", TestDatabase.url("tool"), "--user", "tool");

        assertEquals(0, run.exitCode(), run.err());
        assertTrue(run.out().contains("tool is the login QuerySmith is connected as"), run.out());
    }
}
