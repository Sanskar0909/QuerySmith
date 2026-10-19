package com.querysmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Task 6: LoginCheck.changeableTables. See TestDatabase for the logins. */
class LoginCheckTest {

    @Test
    void readOnlyLoginCanChangeNothing() throws SQLException {
        assertEquals(List.of(), changeableTablesAs("tool"));
    }

    @Test
    void applicationLoginCanChangeItsTables() throws SQLException {
        List<String> tables = changeableTablesAs("app");

        assertTrue(tables.contains("public.products"), tables.toString());
    }

    // Postgres reads Billing.Invoices as billing.invoices unless the names are quoted.
    @Test
    void namesWithCapitalLettersWork() throws SQLException {
        List<String> tables = changeableTablesAs("app");

        assertTrue(tables.contains("Billing.Invoices"), tables.toString());
    }

    // app may insert into events but not into its partition events_2026
    // directly. Rows inserted through events still change data.
    @Test
    void partitionedTablesCount() throws SQLException {
        List<String> tables = changeableTablesAs("app");

        assertTrue(tables.contains("public.events"), tables.toString());
    }

    @Test
    void administratorCanChangeEverything() throws SQLException {
        List<String> tables = changeableTablesAs("postgres");

        assertTrue(tables.contains("public.products"), tables.toString());
        assertTrue(tables.contains("Billing.Invoices"), tables.toString());
    }

    @Test
    void postgresOwnTablesAreLeftOut() throws SQLException {
        // The administrator can change Postgres's own tables too, so this is
        // where they would show up if they were not left out.
        for (String table : changeableTablesAs("postgres")) {
            assertTrue(!table.startsWith("pg_catalog.") && !table.startsWith("information_schema."), table);
        }
    }

    // "tool" has no permission to enter the "Billing" schema. Asking about a
    // table inside it by name fails with "permission denied for schema", which
    // would stop the tool on a perfectly normal database.
    @Test
    void schemaTheLoginCannotEnterIsNotAnError() throws SQLException {
        changeableTablesAs("tool");
    }

    private static List<String> changeableTablesAs(String login) throws SQLException {
        try (Connection conn = TestDatabase.connect(login)) {
            return LoginCheck.changeableTables(conn);
        }
    }
}
