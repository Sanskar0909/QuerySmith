package com.querysmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

/** The command line itself. Needs no database. */
class QuerySmithTest {

    // Port 1 on this machine: nothing listens there, so the connection is refused.
    static final String NOWHERE = "jdbc:postgresql://127.0.0.1:1/shop?user=x&password=x";

    @Test
    void noCommandIsAUsageError() {
        Cli.Run run = Cli.run();

        assertEquals(CommandLine.ExitCode.USAGE, run.exitCode());
        assertTrue(run.err().contains("Missing command"), run.err());
    }

    // Assumes QUERYSMITH_DB is not set where the tests run.
    @Test
    void topWithoutDbIsAUsageError() {
        Cli.Run run = Cli.run("top");

        assertEquals(CommandLine.ExitCode.USAGE, run.exitCode());
        assertTrue(run.err().contains("Missing --db"), run.err());
    }

    // Checked before connecting, so no database is needed to see the mistake.
    @Test
    void topOfZeroIsAUsageError() {
        Cli.Run run = Cli.run("top", "--db", NOWHERE, "--top", "0");

        assertEquals(CommandLine.ExitCode.USAGE, run.exitCode());
        assertTrue(run.err().contains("--top must be at least 1"), run.err());
    }

    @Test
    void unreachableDatabaseIsOneLineNotAStackTrace() {
        Cli.Run run = Cli.run("top", "--db", NOWHERE);

        assertEquals(1, run.exitCode());
        assertTrue(run.err().startsWith("querysmith: "), run.err());
        assertEquals(1, run.err().strip().lines().count(), run.err());
    }
}
