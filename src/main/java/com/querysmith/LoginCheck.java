package com.querysmith;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether the login the tool is connected as can change data.
 *
 * The tool refuses to run when it can (docs/DESIGN.md, M2). A read-only login
 * means that even a bug in the tool cannot change anything: Postgres refuses.
 */
final class LoginCheck {

    private LoginCheck() {}

    /**
     * Every table the connected login could insert into, update, delete from
     * or truncate, written as "schema.table", for example "public.orders".
     *
     * Empty means the login can only read. Postgres's own tables, in
     * pg_catalog and information_schema, are left out.
     *
     * Tested by LoginCheckTest.
     */
    static List<String> changeableTables(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT a.relname, b.nspname FROM pg_class a JOIN pg_namespace b ON a.relnamespace = b.oid WHERE a.relkind IN ('r', 'p') AND b.nspname NOT IN ('pg_catalog', 'information_schema') AND has_table_privilege(a.oid, 'INSERT, UPDATE, DELETE, TRUNCATE')")) {
            while(rs.next()) {
                tables.add(rs.getString(2) + "." + rs.getString(1));
            }

            return tables;
        }
    }
}

