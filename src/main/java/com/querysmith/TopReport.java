package com.querysmith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What {@code querysmith top} prints: the list of queries, and the message
 * when the login is refused. Built from values only, so it is tested without
 * a database.
 */
final class TopReport {

    /** Longer query texts are cut, so that each query stays on one line. */
    static final int QUERY_WIDTH = 80;

    /** The refusal names at most this many tables and counts the rest. */
    static final int TABLES_NAMED = 10;

    static final String N_PLUS_ONE = "[possible N+1]";

    private TopReport() {}

    /**
     * The list, worst first, or a sentence saying why it is empty.
     *
     * @param onlyLogin the --user value, or null
     * @param ownLogin  the login the tool is connected as
     */
    static String report(List<QueryStat> rows, String onlyLogin, String ownLogin) {
        if (rows.isEmpty()) {
            if (onlyLogin != null && onlyLogin.equals(ownLogin)) {
                return line("%s is the login QuerySmith is connected as. Its own queries are never listed.", ownLogin);
            }
            if (onlyLogin != null) {
                return line("No queries from the login %s in this database. "
                        + "The name has to match exactly, capital letters included.", onlyLogin);
            }
            return line("No queries recorded in this database since pg_stat_statements was last reset, "
                    + "apart from QuerySmith's own.");
        }

        StringBuilder out = new StringBuilder();
        out.append(line("Queries by total time, worst first. Share is out of all the time recorded in this"));
        out.append(line("database since pg_stat_statements was last reset."));
        if (onlyLogin != null) {
            out.append(line("Only the login %s is listed, but shares are still out of every login's time.", onlyLogin));
        }
        out.append(System.lineSeparator());

        boolean[] flagged = possibleNPlusOne(rows);
        List<String[]> cells = new ArrayList<>();
        cells.add(new String[] {"#", "share", "total", "calls", "per call", "login", "query"});
        for (int i = 0; i < rows.size(); i++) {
            QueryStat row = rows.get(i);
            cells.add(new String[] {
                String.valueOf(i + 1),
                share(row.sharePercent()),
                duration(row.totalMs()),
                String.format(Locale.ROOT, "%,d", row.calls()),
                duration(row.meanMs()),
                row.login(),
                oneLine(row.query()) + (flagged[i] ? "  " + N_PLUS_ONE : ""),
            });
        }
        out.append(columns(cells));

        for (boolean f : flagged) {
            if (f) {
                out.append(System.lineSeparator());
                out.append(line("%s Fast each run, but called far more often than the other queries listed.", N_PLUS_ONE));
                out.append(line("Often an application running one query per row of another query's result. The"));
                out.append(line("usual fix is in the application: fetch those rows with one query, not one each."));
                break;
            }
        }
        return out.toString();
    }

    /**
     * Which rows look like an N+1: under 1 ms a run on average, and called at
     * least 10 times as often as the median query listed (the middle one when
     * the calls are put in order). Such a query costs little each time, so
     * ranking by time puts it low, yet the real cost is all the round trips
     * between the application and the database.
     */
    static boolean[] possibleNPlusOne(List<QueryStat> rows) {
        boolean[] flagged = new boolean[rows.size()];
        if (rows.isEmpty()) {
            return flagged;
        }
        long[] calls = rows.stream().mapToLong(QueryStat::calls).sorted().toArray();
        int middle = calls.length / 2;
        double median = calls.length % 2 == 1 ? calls[middle] : (calls[middle - 1] + calls[middle]) / 2.0;
        for (int i = 0; i < rows.size(); i++) {
            flagged[i] = rows.get(i).meanMs() < 1.0 && rows.get(i).calls() >= 10 * median;
        }
        return flagged;
    }

    /**
     * The message for a login that can change data, with the SQL that
     * creates one that can only read.
     */
    static String refusal(String login, List<String> tables) {
        StringBuilder out = new StringBuilder();
        out.append(line("querysmith: refusing to run as %s, a login that can change data.", login));
        out.append(System.lineSeparator());
        out.append(line("%s can insert, update, delete or truncate in %d %s:",
                login, tables.size(), tables.size() == 1 ? "table" : "tables"));
        for (String table : tables.stream().sorted().limit(TABLES_NAMED).toList()) {
            out.append(line("  %s", table));
        }
        if (tables.size() > TABLES_NAMED) {
            out.append(line("  and %d more", tables.size() - TABLES_NAMED));
        }
        out.append(System.lineSeparator());
        out.append(line("QuerySmith only reads. With a login that can only read, even a bug in the tool"));
        out.append(line("cannot change anything, because Postgres refuses. As an administrator, create"));
        out.append(line("one and connect as it:"));
        out.append(System.lineSeparator());
        out.append(line("  CREATE ROLE querysmith_ro LOGIN PASSWORD 'choose-a-password';"));
        out.append(line("  -- to read the text of other logins' queries in pg_stat_statements"));
        out.append(line("  GRANT pg_read_all_stats TO querysmith_ro;"));
        out.append(line("  -- to time a query, which means reading the tables it reads"));
        out.append(line("  GRANT SELECT ON ALL TABLES IN SCHEMA public TO querysmith_ro;"));
        out.append(System.lineSeparator());
        out.append(line("For tables in another schema, also run these two for each one:"));
        out.append(System.lineSeparator());
        out.append(line("  GRANT USAGE ON SCHEMA <schema> TO querysmith_ro;"));
        out.append(line("  GRANT SELECT ON ALL TABLES IN SCHEMA <schema> TO querysmith_ro;"));
        return out.toString();
    }

    /** 39.2%, or <0.1% for a share too small to show as 0.0%. */
    static String share(double percent) {
        return percent > 0 && percent < 0.05 ? "<0.1%" : String.format(Locale.ROOT, "%.1f%%", percent);
    }

    /** 112.6 s, 208.1 ms, 0.004 ms. */
    static String duration(double ms) {
        if (ms >= 1000) {
            return String.format(Locale.ROOT, "%,.1f s", ms / 1000);
        }
        return String.format(Locale.ROOT, ms >= 1 ? "%.1f ms" : "%.3f ms", ms);
    }

    /** The query on one line, cut to QUERY_WIDTH characters. */
    static String oneLine(String query) {
        String flat = query.strip().replaceAll("\\s+", " ");
        return flat.length() <= QUERY_WIDTH ? flat : flat.substring(0, QUERY_WIDTH - 3) + "...";
    }

    // Numbers line up on the right, words on the left. The last column is
    // not padded, so lines carry no trailing spaces.
    private static String columns(List<String[]> cells) {
        int[] widths = new int[cells.get(0).length];
        for (String[] row : cells) {
            for (int c = 0; c < row.length; c++) {
                widths[c] = Math.max(widths[c], row[c].length());
            }
        }
        StringBuilder out = new StringBuilder();
        for (String[] row : cells) {
            String[] padded = Arrays.copyOf(row, row.length);
            for (int c = 0; c < row.length - 1; c++) {
                String pad = " ".repeat(widths[c] - row[c].length());
                padded[c] = c <= 4 ? pad + row[c] : row[c] + pad;
            }
            out.append(String.join("  ", padded)).append(System.lineSeparator());
        }
        return out.toString();
    }

    private static String line(String format, Object... args) {
        return String.format(Locale.ROOT, format, args) + System.lineSeparator();
    }
}
