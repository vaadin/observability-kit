/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.internal.StringUtil;

/**
 * What survives {@link ClientResourceLoader}'s comment stripping.
 * <p>
 * The loader does not inject the client script as written: it runs
 * {@link StringUtil#removeComments} over it first, and that parser is not a
 * JavaScript parser. A double slash inside a regex literal reads to it as the
 * start of a line comment, so it deletes the rest of that line — and since a
 * regex literal for a URL scheme ends in an escaped slash followed by the
 * closing delimiter, writing one silently truncated the whole file and the
 * collector never installed. No unit test noticed, because every unit test
 * reads the file directly; the only thing that failed was an IT, asserting that
 * some {@code vaadin.client.*} meter had arrived.
 * <p>
 * So this checks the artefact that actually reaches the browser rather than the
 * source. It cannot parse JavaScript, but it can tell that nothing was eaten:
 * brackets balance, and every function the file declares is still declared.
 */
class ClientResourceIntegrityTest {

    private static final String RESOURCE = "META-INF/frontend/VaadinMetricsClient.js";

    /**
     * The functions the collector is built out of. Named explicitly rather than
     * counted, so that a line disappearing says which one went with it.
     */
    private static final List<String> FUNCTIONS = List.of("monotonicNow",
            "connectionStore", "isLoading", "normalizeState", "isOfflineState",
            "offline", "offlineElapsed", "bufferedMs", "pushSample",
            "currentRoute", "persist", "restore", "priority", "priorityFirst",
            "makeRoom", "flush", "settle", "detailText", "detailsEnabled",
            "hasScheme", "numberStart", "isLocation", "hasLineAndColumn",
            "separatorIn", "partOfPath", "hasUserInfo", "parseFrame",
            "firstFrame", "errorDetail");

    private static String source() throws IOException {
        try (InputStream in = ClientResourceIntegrityTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            Assertions.assertNotNull(in, RESOURCE + " is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyFunctionSurvivesTheCommentStripping() throws IOException {
        String source = source();
        String injected = StringUtil.removeComments(source, true);

        for (String function : FUNCTIONS) {
            Assertions.assertTrue(source.contains("function " + function + "("),
                    () -> "this test is out of date: the collector no longer "
                            + "declares " + function);
            Assertions.assertTrue(
                    injected.contains("function " + function + "("),
                    () -> "comment stripping ate the declaration of " + function
                            + " -- something on its line reads as "
                            + "the start of a comment");
        }
    }

    @Test
    void bracketsStillBalanceAfterTheCommentStripping() throws IOException {
        String injected = StringUtil.removeComments(source(), true);

        // A crude parser, and enough: what a swallowed line does is leave an
        // opening bracket without its partner. Counting is safe here because
        // the collector holds no bracket characters inside string literals --
        // which the next assertion is what keeps true.
        for (char[] pair : new char[][] { { '{', '}' }, { '(', ')' },
                { '[', ']' } }) {
            long open = injected.chars().filter(c -> c == pair[0]).count();
            long close = injected.chars().filter(c -> c == pair[1]).count();
            Assertions.assertEquals(open, close,
                    () -> "unbalanced " + pair[0] + pair[1]
                            + " after comment stripping, so a line was eaten");
        }
    }

    @Test
    void theSourceHoldsNoDoubleSlashOutsideAComment() throws IOException {
        // The rule that keeps the above true, stated where it can be checked:
        // a double slash anywhere but at the start of a comment is a line the
        // stripper will truncate. This is why hasScheme is a character scan
        // and not a pattern.
        int line = 0;
        for (String text : source().split("\n")) {
            line++;
            String trimmed = text.strip();
            if (trimmed.startsWith("//")) {
                continue;
            }
            int at = text.indexOf("//");
            if (at < 0) {
                continue;
            }
            // A trailing comment is fine; what is not is a double slash with
            // code after it on the same line.
            String after = text.substring(at + 2);
            int number = line;
            Assertions.assertFalse(after.contains(";") || after.contains("{"),
                    () -> "line " + number + " has code after a double slash, "
                            + "which comment stripping will delete: " + text);
        }
    }
}
