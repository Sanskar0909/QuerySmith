package com.querysmith;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(
        name = "top",
        mixinStandardHelpOptions = true,
        description = "List the queries costing the database the most.")
final class TopCommand implements Callable<Integer> {

    // Read from an environment variable when not given, so a password in the
    // URL does not have to be typed into the shell history every time.
    @Option(
            names = "--db",
            paramLabel = "<jdbc-url>",
            defaultValue = "${env:QUERYSMITH_DB}",
            description = "JDBC URL of the database, user and password included. "
                    + "Defaults to the QUERYSMITH_DB environment variable.")
    String db;

    @Option(
            names = "--top",
            paramLabel = "<n>",
            defaultValue = "10",
            description = "How many queries to list, worst first. Default: ${DEFAULT-VALUE}.")
    int top;

    @Option(
            names = "--user",
            paramLabel = "<login>",
            description = "Only list the queries run by this login, for example the application's. "
                    + "Shares stay out of every login's time.")
    String user;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() throws SQLException {
        if (db == null || db.isBlank()) {
            throw new ParameterException(spec.commandLine(), "Missing --db, and QUERYSMITH_DB is not set");
        }
        if (top < 1) {
            throw new ParameterException(spec.commandLine(), "--top must be at least 1");
        }

        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try (Connection conn = connect(db)) {
            String login;
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery(
                            // server_version reads like "18.6 (Debian 18.6-1.pgdg13+2)"; keep the "18.6".
                            "SELECT current_user, current_database(), split_part(current_setting('server_version'), ' ', 1)")) {
                rs.next();
                login = rs.getString(1);
                out.printf("Connected as %s to %s (PostgreSQL %s)%n%n", login, rs.getString(2), rs.getString(3));
            }

            // Before anything else is run: docs/DESIGN.md, M2.
            List<String> changeable = LoginCheck.changeableTables(conn);
            if (!changeable.isEmpty()) {
                err.print(TopReport.refusal(login, changeable));
                err.flush();
                return 1;
            }

            out.print(TopReport.report(TopQueries.top(conn, top, user), user, login));
            // print() does not flush on its own, and main() exits straight after.
            out.flush();
        }
        return 0;
    }

    private static Connection connect(String url) throws SQLException {
        Properties props = new Properties();
        // Shows as application_name in pg_stat_activity, so whoever looks at
        // the database's live connections can see which one is this tool.
        props.setProperty("ApplicationName", "querysmith");
        return DriverManager.getConnection(url, props);
    }
}
