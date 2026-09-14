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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * source, and the check that settles it is {@code node --check} over the
 * stripped text: a truncated line usually leaves a syntax error, which counting
 * brackets and looking for function declarations can only approximate — a regex
 * or template literal holding an unbalanced bracket passes those and still
 * breaks the injected script. The cheaper checks are kept because they say
 * <em>which</em> declaration went missing, and because they run on a machine
 * with no node.
 * <p>
 * Both scripts the loader injects are checked, not just the collector: the
 * dev-tools panel goes through the same stripping, and its failure mode is the
 * quieter one — a truncated panel script leaves the developer with no panel and
 * no meter or insight to notice missing.
 */
class ClientResourceIntegrityTest {

    private static final String RESOURCE = "META-INF/frontend/VaadinMetricsClient.js";

    private static final String PANEL_RESOURCE = "META-INF/frontend/VaadinObservabilityDevTools.js";

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

    private static String source(String resource) throws IOException {
        try (InputStream in = ClientResourceIntegrityTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            Assertions.assertNotNull(in, resource + " is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyFunctionSurvivesTheCommentStripping() throws IOException {
        String source = source(RESOURCE);
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

    /**
     * The check the others approximate: hand the stripped text to a JavaScript
     * parser and see whether it is still JavaScript.
     * <p>
     * Skipped rather than failed where node is missing, the way the browser
     * suites are with {@code -Dskip.js.tests} — this is the same tool, and a
     * machine without it should not fail a build over the one check that needs
     * it.
     */
    @ParameterizedTest
    @ValueSource(strings = { RESOURCE, PANEL_RESOURCE })
    void theStrippedScriptStillParses(String resource) throws Exception {
        Assumptions.assumeTrue(nodeIsAvailable(), "node is not on PATH");

        Path file = Files.createTempFile("observability-kit-stripped-", ".js");
        try {
            Files.writeString(file,
                    StringUtil.removeComments(source(resource), true),
                    StandardCharsets.UTF_8);
            Process node = new ProcessBuilder("node", "--check",
                    file.toAbsolutePath().toString()).redirectErrorStream(true)
                    .start();
            String output = new String(node.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            Assertions.assertTrue(node.waitFor(60, TimeUnit.SECONDS),
                    "node --check did not finish");
            Assertions.assertEquals(0, node.exitValue(),
                    () -> resource + " does not parse after comment stripping, "
                            + "so a line was eaten:\n" + output);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static boolean nodeIsAvailable() {
        try {
            Process node = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true).start();
            return node.waitFor(60, TimeUnit.SECONDS) && node.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The collector only. Counting brackets assumes none of them sits
     * unmatched inside a string literal, which holds for the collector and
     * does not for the panel — its route-template parsing looks for the
     * literal {@code "?("}, and the count is off by one per such string. That
     * is the limit of this check rather than a fault in the script, and
     * {@link #theStrippedScriptStillParses} is what covers the panel properly.
     */
    @Test
    void bracketsStillBalanceAfterTheCommentStripping() throws IOException {
        String injected = StringUtil.removeComments(source(RESOURCE), true);

        // A crude parser, and enough for a file that keeps to the rule: what a
        // swallowed line does is leave an opening bracket without its partner.
        for (char[] pair : new char[][] { { '{', '}' }, { '(', ')' },
                { '[', ']' } }) {
            long open = injected.chars().filter(c -> c == pair[0]).count();
            long close = injected.chars().filter(c -> c == pair[1]).count();
            Assertions.assertEquals(open, close,
                    () -> "unbalanced " + pair[0] + pair[1]
                            + " after comment stripping, so a line was eaten");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { RESOURCE, PANEL_RESOURCE })
    void theSourceHoldsNoDoubleSlashOutsideAComment(String resource)
            throws IOException {
        // The rule that keeps the above true, stated where it can be checked:
        // a double slash anywhere but at the start of a comment is a line the
        // stripper will truncate. This is why hasScheme is a character scan
        // and not a pattern, and why neither script writes a URL out in full.
        int line = 0;
        for (String text : source(resource).split("\n")) {
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
