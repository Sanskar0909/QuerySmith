package com.querysmith;

import java.util.Locale;
import java.util.Set;

/**
 * Which statements the tool tries at all (docs/DESIGN.md, M3).
 *
 * Only reads: a statement whose first word is SELECT, WITH or VALUES. Anything
 * else is reported and never sent to the database.
 *
 * This check reads only the text, so it can be fooled:
 * {@code WITH x AS (UPDATE ...) SELECT ...} starts like a read. The read-only
 * transaction in SafeRun is what actually stops that one. This check is what
 * lets the report say "a write" in plain words for the ordinary cases, and
 * keeps those statements from being sent at all.
 *
 * Tested by StatementCheckTest.
 */
final class StatementCheck {

    private StatementCheck() {}

    /**
     * The statement's first word in capitals, for example "SELECT" or
     * "UPDATE".
     *
     * Spaces, tabs and line breaks in front of it are skipped, and so are
     * comments: from {@code --} to the end of the line, and block comments,
     * which open with a slash and a star and close with a star and a slash.
     * The word ends at the first character that is not a letter.
     *
     * Empty if there is no word at all.
     */
    static String firstWord(String query) {
        int i = 0;
        int end = query.length();
        // Step over space and comments until something else is in front.
        while (i < end) {
            if (Character.isWhitespace(query.charAt(i))) {
                i++;
            } else if (query.startsWith("--", i)) {
                int lineBreak = query.indexOf('\n', i);
                i = lineBreak < 0 ? end : lineBreak + 1;
            } else if (query.startsWith("/*", i)) {
                // A comment that never closes runs to the end: no word.
                int close = query.indexOf("*/", i + 2);
                i = close < 0 ? end : close + 2;
            } else {
                break;
            }
        }
        int start = i;
        while (i < end && Character.isLetter(query.charAt(i))) {
            i++;
        }
        return query.substring(start, i).toUpperCase(Locale.ROOT);
    }

    /** True if the first word is SELECT, WITH or VALUES. */
    static boolean isRead(String query) {
        String firstWord = firstWord(query);
        Set<String> acceptedFirstWords = Set.of("SELECT", "WITH", "VALUES");

        return acceptedFirstWords.contains(firstWord);
    }
}
