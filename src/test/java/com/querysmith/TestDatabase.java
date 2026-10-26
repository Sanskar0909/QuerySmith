package com.querysmith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One throwaway Postgres for all the tests, set up like a small copy of the lab.
 *
 * <pre>
 *   postgres   the administrator
 *   app        the application: reads and changes its tables
 *   tool       QuerySmith's login: reads only
 *   QuerySmith the same, with capital letters in its name
 * </pre>
 *
 * Each login's password is its own name. Started the first time a test asks
 * for it; Testcontainers throws it away when the tests finish. Needs Docker.
 */
final class TestDatabase {

    static final String DATABASE = "shop";

    private static PostgreSQLContainer postgres;

    private TestDatabase() {}

    /** Connects to the shop database as one of the logins above. */
    static Connection connect(String login) throws SQLException {
        return connect(login, DATABASE);
    }

    /** Connects to any database on the same server. */
    static Connection connect(String login, String database) throws SQLException {
        PostgreSQLContainer pg = container();
        String url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + database;
        Properties props = new Properties();
        props.setProperty("user", login);
        props.setProperty("password", login);
        // A test whose code under test forgot its time limit fails after
        // 30 s, instead of waiting out a pg_sleep(600) or a lock forever.
        props.setProperty("socketTimeout", "30");
        return DriverManager.getConnection(url, props);
    }

    /** A JDBC URL with the login and password in it, the way --db takes one. */
    static String url(String login) throws SQLException {
        PostgreSQLContainer pg = container();
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + DATABASE
                + "?user=" + login + "&password=" + login;
    }

    static void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static synchronized PostgreSQLContainer container() throws SQLException {
        if (postgres == null) {
            PostgreSQLContainer pg = new PostgreSQLContainer("postgres:18")
                    .withDatabaseName(DATABASE)
                    .withUsername("postgres")
                    .withPassword("postgres")
                    // As in the lab: pg_stat_statements only records anything
                    // if it is loaded when the server starts.
                    .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements", "-c", "fsync=off");
            pg.start();
            postgres = pg;
            setUp();
        }
        return postgres;
    }

    private static void setUp() throws SQLException {
        String[] statements = {
            "CREATE EXTENSION pg_stat_statements",

            "CREATE TABLE products (id int PRIMARY KEY, name text NOT NULL)",
            "INSERT INTO products SELECT g, 'product ' || g FROM generate_series(1, 100) g",

            // A second schema, with capital letters in both names. Postgres
            // folds unquoted names to lower case, so these only work quoted:
            // "Billing"."Invoices". Real databases have names like this.
            "CREATE SCHEMA \"Billing\"",
            "CREATE TABLE \"Billing\".\"Invoices\" (id int PRIMARY KEY)",

            // A partitioned table: "events" holds no rows itself, and each
            // row goes into one of its partitions. In pg_class the parent is
            // relkind 'p', not 'r'.
            "CREATE TABLE events (id int, at date) PARTITION BY RANGE (at)",
            "CREATE TABLE events_2026 PARTITION OF events FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')",

            // A second database on the same server. Queries run in it still
            // land in pg_stat_statements, which is shared by the whole server.
            "CREATE DATABASE other",

            // A counter that only goes up. nextval() moves it on, and that
            // is a change, even inside a SELECT.
            "CREATE SEQUENCE ticket_numbers",

            "CREATE ROLE app LOGIN PASSWORD 'app'",
            "GRANT SELECT, INSERT, UPDATE ON products TO app",
            "GRANT USAGE ON SEQUENCE ticket_numbers TO app",
            "GRANT USAGE ON SCHEMA \"Billing\" TO app",
            "GRANT INSERT ON \"Billing\".\"Invoices\" TO app",
            // Only on the parent. Inserting through it still puts rows into
            // events_2026: Postgres checks permission on the parent alone.
            "GRANT INSERT ON events TO app",

            // Like querysmith_ro in the lab. It is given nothing at all in
            // "Billing", not even permission to look inside the schema, so
            // the check has to cope with a schema its own login cannot enter.
            "CREATE ROLE tool LOGIN PASSWORD 'tool'",
            "GRANT SELECT ON ALL TABLES IN SCHEMA public TO tool",
            "GRANT pg_read_all_stats TO tool",

            // A second read-only login, with capital letters. current_user
            // gives QuerySmith, but Postgres reads QuerySmith typed into SQL
            // without quotes as querysmith, a login that does not exist.
            "CREATE ROLE \"QuerySmith\" LOGIN PASSWORD 'QuerySmith'",
            "GRANT SELECT ON ALL TABLES IN SCHEMA public TO \"QuerySmith\"",
            "GRANT pg_read_all_stats TO \"QuerySmith\"",
        };
        try (Connection admin = connect("postgres")) {
            for (String sql : statements) {
                execute(admin, sql);
            }
        }
    }
}
