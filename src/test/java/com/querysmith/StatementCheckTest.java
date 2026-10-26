package com.querysmith;

import static com.querysmith.StatementCheck.firstWord;
import static com.querysmith.StatementCheck.isRead;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** M3: StatementCheck, which statements are tried at all. Needs no database. */
class StatementCheckTest {

    @Test
    void firstWordComesBackInCapitals() {
        assertEquals("SELECT", firstWord("SELECT id FROM orders WHERE customer_id = $1"));
        assertEquals("SELECT", firstWord("select id from orders where customer_id = $1"));
        assertEquals("UPDATE", firstWord("UPDATE orders SET status = $1 WHERE id = $2"));
    }

    // pg_stat_statements keeps the text as the application sent it, spaces
    // and line breaks included.
    @Test
    void spaceInFrontIsSkipped() {
        assertEquals("SELECT", firstWord("\n   \tSELECT 1"));
    }

    // Many frameworks put a comment in front of every query they send,
    // naming the code that sent it, and pg_stat_statements keeps it.
    @Test
    void commentsInFrontAreSkipped() {
        assertEquals("SELECT", firstWord("/* controller='orders' */ SELECT id FROM orders"));
        assertEquals("SELECT", firstWord("-- nightly report\nSELECT count(*) FROM orders"));
        assertEquals("DELETE", firstWord("/* one */ -- two\n  /* three */DELETE FROM orders WHERE id = $1"));
    }

    @Test
    void wordEndsWhereTheLettersStop() {
        assertEquals("VALUES", firstWord("VALUES(1), (2)"));
        assertEquals("SELECT", firstWord("SELECT\n  id\nFROM orders"));
    }

    @Test
    void noWordAtAllIsEmpty() {
        assertEquals("", firstWord(""));
        assertEquals("", firstWord("   \n"));
        assertEquals("", firstWord("/* only a comment */"));
        assertEquals("", firstWord("-- only a comment"));
        // Postgres would refuse this one too: the comment never closes.
        assertEquals("", firstWord("/* never closed SELECT 1"));
    }

    @Test
    void readsAreTried() {
        assertTrue(isRead("SELECT id FROM orders WHERE customer_id = $1"));
        assertTrue(isRead("with recent AS (SELECT id FROM orders) SELECT count(*) FROM recent"));
        assertTrue(isRead("VALUES (1), (2)"));
        assertTrue(isRead("/* page=orders */ SELECT id FROM orders"));
    }

    @Test
    void everythingElseIsNot() {
        List<String> notReads = List.of(
                "INSERT INTO orders (customer_id, status, total_cents, created_at) VALUES ($1, $2, $3, now())",
                "UPDATE orders SET status = $1 WHERE id = $2 AND status = $3",
                "DELETE FROM orders WHERE id = $1",
                "MERGE INTO orders o USING returns r ON o.id = r.order_id WHEN MATCHED THEN DELETE",
                "SET work_mem = $1",
                "BEGIN",
                "COMMIT",
                "VACUUM orders",
                // The tool adds EXPLAIN itself.
                "EXPLAIN SELECT 1",
                "");
        for (String query : notReads) {
            assertFalse(isRead(query), query);
        }
    }

    // SELECT is in both, but neither starts with it.
    @Test
    void readWordFurtherOnDoesNotCount() {
        assertFalse(isRead("INSERT INTO old_orders SELECT * FROM orders WHERE created_at < $1"));
        assertFalse(isRead("/* SELECT */ UPDATE orders SET status = $1 WHERE id = $2"));
    }

    // Experiment 6, statement 5. Its first word is WITH, so it gets past
    // this check. The read-only transaction is what refuses it.
    @Test
    void updateInsideWithGetsPastThisCheck() {
        assertTrue(isRead("WITH changed AS (UPDATE orders SET status = $1 WHERE id = $2 RETURNING id) SELECT * FROM changed"));
    }
}
