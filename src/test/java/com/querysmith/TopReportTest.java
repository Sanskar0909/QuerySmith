package com.querysmith;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** What `querysmith top` prints. Needs no database. */
class TopReportTest {

    // Shaped like the lab after a minute of traffic. The last one is called
    // about 100 times as often as the next and takes a few microseconds a run.
    static final List<QueryStat> LAB = List.of(
            new QueryStat("shop_app", 541, 112_560, 208.06, 39.19,
                    "SELECT product_id, quantity, unit_price_cents\nFROM order_items\nWHERE order_id = $1"),
            new QueryStat("shop_app", 786, 55_726, 70.9, 19.4, "SELECT id, status FROM orders WHERE customer_id = $1"),
            new QueryStat("shop_app", 68, 44_109, 648.7, 15.4, "SELECT c.email, count(*) FROM support_tickets t"),
            new QueryStat("postgres", 1, 153.2, 153.2, 0.05, "SELECT t.table_name FROM tables t"),
            new QueryStat("shop_app", 81_051, 356, 0.0044, 0.12, "SELECT name, price_cents FROM products WHERE id = $1"));

    @Test
    void eachRowShowsShareTotalCallsTimePerCallAndLogin() {
        String out = TopReport.report(LAB, null, "querysmith_ro");

        String first = lineContaining(out, "order_items");
        assertTrue(first.matches("1\\s+39\\.2%\\s+112\\.6 s\\s+541\\s+208\\.1 ms\\s+shop_app\\s+SELECT product_id.*"), first);
        String fast = lineContaining(out, "products WHERE");
        assertTrue(fast.matches("5\\s+0\\.1%\\s+356\\.0 ms\\s+81,051\\s+0\\.004 ms\\s+shop_app\\s+SELECT name.*"), fast);
    }

    @Test
    void columnsLineUp() {
        String out = TopReport.report(LAB, null, "querysmith_ro");

        int header = lineContaining(out, "per call").indexOf("query");
        assertEquals(header, lineContaining(out, "order_items").indexOf("SELECT"), out);
        assertEquals(header, lineContaining(out, "table_name").indexOf("SELECT"), out);
    }

    @Test
    void queryIsShownOnOneLineAndCutWhenLong() {
        String longQuery = "SELECT " + "a_long_column_name, ".repeat(20) + "id FROM t";

        assertEquals("SELECT product_id, quantity FROM order_items WHERE order_id = $1",
                TopReport.oneLine("  SELECT product_id, quantity\n  FROM order_items\n\tWHERE order_id = $1\n"));
        assertEquals(TopReport.QUERY_WIDTH, TopReport.oneLine(longQuery).length());
        assertTrue(TopReport.oneLine(longQuery).endsWith("..."));
    }

    @Test
    void fastQueryCalledFarMoreThanTheRestIsFlagged() {
        assertArrayEquals(new boolean[] {false, false, false, false, true}, TopReport.possibleNPlusOne(LAB));

        String out = TopReport.report(LAB, null, "querysmith_ro");
        assertTrue(lineContaining(out, "products WHERE").endsWith(TopReport.N_PLUS_ONE), out);
        assertTrue(out.contains("one query per row"), out);
    }

    // Called 100 times as often, but 5 ms a run: slow in its own right.
    @Test
    void frequentButSlowIsNotFlagged() {
        List<QueryStat> rows = new ArrayList<>(LAB.subList(0, 4));
        rows.add(new QueryStat("shop_app", 81_051, 405_255, 5.0, 60, "SELECT 1"));

        assertArrayEquals(new boolean[] {false, false, false, false, false}, TopReport.possibleNPlusOne(rows));
    }

    @Test
    void noFlagNoExplanation() {
        String out = TopReport.report(LAB.subList(0, 3), null, "querysmith_ro");

        assertTrue(!out.contains(TopReport.N_PLUS_ONE), out);
    }

    @Test
    void sharesAndTimesAreReadable() {
        assertEquals("39.2%", TopReport.share(39.19));
        assertEquals("<0.1%", TopReport.share(0.01));
        assertEquals("0.0%", TopReport.share(0));
        assertEquals("1,234.6 s", TopReport.duration(1_234_567));
        assertEquals("208.1 ms", TopReport.duration(208.06));
        assertEquals("0.004 ms", TopReport.duration(0.0044));
    }

    @Test
    void narrowedListSaysSharesAreStillOutOfEveryone() {
        String out = TopReport.report(LAB.subList(0, 1), "shop_app", "querysmith_ro");

        assertTrue(out.contains("Only the login shop_app is listed"), out);
    }

    @Test
    void emptyListSaysWhy() {
        assertTrue(TopReport.report(List.of(), null, "querysmith_ro").startsWith("No queries recorded"));
        assertTrue(TopReport.report(List.of(), "shop_ap", "querysmith_ro").contains("No queries from the login shop_ap"));
        assertTrue(TopReport.report(List.of(), "querysmith_ro", "querysmith_ro")
                .contains("Its own queries are never listed"));
    }

    @Test
    void refusalNamesTheTablesAndHowToMakeAReadOnlyLogin() {
        String out = TopReport.refusal("shop_app", List.of("public.orders", "public.products"));

        assertTrue(out.startsWith("querysmith: refusing to run as shop_app"), out);
        assertTrue(out.contains("in 2 tables:"), out);
        assertTrue(out.contains("  public.orders" + System.lineSeparator()), out);
        assertTrue(out.contains("GRANT pg_read_all_stats TO querysmith_ro;"), out);
        assertTrue(out.contains("GRANT SELECT ON ALL TABLES IN SCHEMA public TO querysmith_ro;"), out);
    }

    // An administrator can change every table there is.
    @Test
    void refusalCountsTablesBeyondTheFirstTen() {
        List<String> tables = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            // t01, not t1: names are listed sorted, and t10 sorts before t2.
            tables.add(String.format("public.t%02d", i));
        }
        String out = TopReport.refusal("postgres", tables);

        assertTrue(out.contains("  public.t10" + System.lineSeparator()), out);
        assertTrue(!out.contains("public.t11"), out);
        assertTrue(out.contains("  and 15 more"), out);
    }

    private static String lineContaining(String text, String part) {
        return text.lines()
                .filter(line -> line.contains(part))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line with " + part + " in:\n" + text));
    }
}
