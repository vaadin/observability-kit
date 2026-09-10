/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The corpus the frame filter is defined by, read from
 * {@code stack-frames-corpus.tsv} — the same file the browser copy's tests
 * read, so that "the two agree" is enforced rather than asserted. When they
 * disagree, the server's answer is the one that matters.
 */
class StackFramesTest {

    private static final String CORPUS = "stack-frames-corpus.tsv";

    /** What a corpus row says the two entry points must do with its text. */
    private enum Verdict {
        /**
         * A stack line: {@code parse} and {@code frame} both give the location.
         */
        KEEP,
        /**
         * A bare location: {@code parse} refuses it, {@code frame} returns it.
         */
        LOC,
        /** Neither: nothing comes out of it either way. */
        DROP
    }

    /**
     * One corpus row: the text, what must happen to it, and the location that
     * must come out where one does.
     */
    private record Case(String line, Verdict verdict, String location) {
    }

    private static List<Case> corpus() throws IOException {
        List<Case> cases = new ArrayList<>();
        try (InputStream in = StackFramesTest.class.getClassLoader()
                .getResourceAsStream(CORPUS)) {
            Assertions.assertNotNull(in, CORPUS + " is missing");
            for (String rawLine : new String(in.readAllBytes(),
                    StandardCharsets.UTF_8).split("\n")) {
                // Split the line with its line ending already off: a CRLF
                // checkout would otherwise leave a \r on the last column, so
                // every expected location would differ from the real one by a
                // character that does not show up in a diff.
                String raw = rawLine.endsWith("\r")
                        ? rawLine.substring(0, rawLine.length() - 1)
                        : rawLine;
                if (raw.strip().isEmpty() || raw.startsWith("#")) {
                    continue;
                }
                String[] columns = raw.split("\t", -1);
                Verdict verdict = switch (columns[0]) {
                case "keep" -> Verdict.KEEP;
                case "loc" -> Verdict.LOC;
                case "drop" -> Verdict.DROP;
                default -> throw new AssertionError(
                        "unknown verdict in " + CORPUS + ": " + raw);
                };
                Assertions.assertEquals(verdict == Verdict.DROP ? 2 : 3,
                        columns.length, () -> "a " + columns[0]
                                + " row needs its expected location: " + raw);
                cases.add(new Case(columns[1], verdict,
                        verdict == Verdict.DROP ? null : columns[2]));
            }
        }
        return cases;
    }

    /**
     * Separators that turn free text into one unbroken "path". The first four
     * are whitespace to JavaScript but not to Java's ASCII {@code \s}; the last
     * two are whitespace to neither, so no {@code \s}-based rule and no
     * {@code UNICODE_CHARACTER_CLASS} closes them. Generated rather than listed
     * in the corpus file, since they are invisible in a text editor.
     */
    static final List<String> BLANKS = List.of(" ", " ", " ", "　", "​", "﻿");

    static List<String> blankSeparated(String blank) {
        return List.of(
                "at f (/x/" + blank + "card" + blank + "number" + blank + "4111"
                        + blank + "declined.x:1:2)",
                "Error:" + blank + "failed" + blank + "to" + blank + "fetch"
                        + blank + "https://user@api.example.com:8443",
                "token" + blank + "abc" + blank
                        + "4111@evil.example.com/x.js:1:2");
    }

    @Test
    void parseGivesTheStackLineRuleTheCorpusNames() throws IOException {
        List<Case> cases = corpus();
        Assertions.assertTrue(cases.size() > 60,
                () -> "the corpus looks truncated: " + cases.size() + " cases");
        for (Case entry : cases) {
            StackFrames.Frame frame = StackFrames.parse(entry.line());
            if (entry.verdict() == Verdict.KEEP) {
                Assertions.assertNotNull(frame,
                        () -> "parse found no location in: " + entry.line());
                Assertions.assertEquals(entry.location(), frame.location(),
                        () -> "parse gave the wrong location for: "
                                + entry.line());
            } else {
                // Including LOC: a bare location is not a stack line, and
                // parse saying so is what lets frame resolve the overlap.
                Assertions.assertNull(frame,
                        () -> "parse read a stack line out of: "
                                + entry.line());
            }
        }
    }

    @Test
    void frameGivesTheSameLocationForEveryCorpusRow() throws IOException {
        // The loop that should have existed from the start. `parse` is the
        // stack-line rule, but `frame` is the only entry point production
        // uses -- it tries a location first and falls back to parsing -- so a
        // corpus checked only through `parse` says nothing about what reaches
        // the payload. Three of the last four defects lived exactly there: a
        // scoped path mangled, a whole stack line published raw, and a
        // legitimate CDN path refused, all with a green suite.
        //
        // The `loc` rows are what make this loop bite: they are bare
        // locations, so they exercise the branch `parse` never sees.
        int bareLocations = 0;
        for (Case entry : corpus()) {
            StackFrames.Frame frame = StackFrames.frame(entry.line());
            if (entry.verdict() == Verdict.DROP) {
                Assertions.assertNull(frame,
                        () -> "frame found a location in: " + entry.line());
                continue;
            }
            if (entry.verdict() == Verdict.LOC) {
                bareLocations++;
            }
            Assertions.assertNotNull(frame,
                    () -> "frame found no location in: " + entry.line());
            Assertions.assertEquals(entry.location(), frame.location(),
                    () -> "frame gave the wrong location for: " + entry.line());
        }
        int covered = bareLocations;
        Assertions.assertTrue(covered >= 10,
                () -> "the corpus needs bare-location rows for this loop to "
                        + "test anything frame-specific; found " + covered);
    }

    @Test
    void aRetainedLocationNeverCarriesTheFunctionName() throws IOException {
        // The invariant the whole class exists for, stated over the corpus
        // rather than over a handful of examples: a page can set a function's
        // name to any string, so no part of it may ride along in the field
        // that is published whatever the detail policy says.
        for (Case entry : corpus()) {
            if (entry.verdict() == Verdict.DROP) {
                continue;
            }
            String location = StackFrames.frame(entry.line()).location();
            Assertions.assertFalse(location.contains(" "),
                    () -> "a location has no spaces in it: " + location);
            Assertions.assertFalse(location.startsWith("at "),
                    () -> "the V8 prefix is not part of a location: "
                            + location);
            // Idempotence, which is the durable way to say "no name came
            // along". A location that still carried one -- the
            // "renderChart@chart.js:44:13" shape -- would not survive being
            // asked again, because the @ in it is a separator. Stated this
            // way rather than as a rule about characters, since the rule for
            // which @ is a separator is exactly what keeps being refined.
            Assertions.assertEquals(location, StackFrames.location(location),
                    () -> "a retained location is itself a location: "
                            + location);
        }
    }

    @Test
    void aSeparatorOneEngineCallsWhitespaceIsNotAFrameLine() {
        // Asserting null, not merely "the payload's words did not survive".
        // The weaker form of this assertion passed while
        // "token abc 4111@evil.example.com/x.js:1:2" was publishing
        // evil.example.com/x.js:1:2 -- a line that is not a frame, parsed as
        // one, displacing whatever real frame came after it. What has to hold
        // is that a non-frame line yields nothing at all.
        for (String blank : BLANKS) {
            for (String line : blankSeparated(blank)) {
                Assertions.assertNull(StackFrames.parse(line),
                        () -> "parsed as a frame: " + line);
            }
        }
    }

    @Test
    void aScopedPackagePathSurvivesWhole() {
        // The @ in /node_modules/@vaadin/... is not a name separator. Read as
        // one, it published a path that does not exist and filed the chopped
        // prefix as a function name -- on the honest path, for most of the
        // component library. `frame` resolves the overlap by asking whether
        // the text is already a location before asking whether it can be
        // split.
        String path = "/node_modules/@vaadin/router/router.js:12:3";
        StackFrames.Frame frame = StackFrames.frame(path);

        Assertions.assertNotNull(frame);
        Assertions.assertEquals(path, frame.location(),
                "the path has to come back whole");
        Assertions.assertNull(frame.function(),
                "a bare location has no function name to give");
    }

    @Test
    void aWholeStackLineInTheFrameFieldIsCanonicalisedRatherThanPublished() {
        // frame() accepts a whole stack line, which an older page still sends.
        // Such a value used to be published verbatim, because a name glued to
        // a path is shaped like a location -- and an agent following
        // evidence.frame then opens nothing. Requiring every @ in a location
        // to follow a slash means location() refuses it and parse() takes it
        // apart instead: the path lands in frame, the name in the gated field.
        StackFrames.Frame frame = StackFrames
                .frame("renderChart@chart.js:44:13");

        Assertions.assertNotNull(frame);
        Assertions.assertEquals("chart.js:44:13", frame.location(),
                "the part someone can open");
        Assertions.assertEquals("renderChart", frame.function(),
                "and the part the page named, kept apart");
    }

    @Test
    void aFrameWithNoNameReportsNoNameRatherThanAnEmptyOne() {
        // "at (/a.js:1:2)" -- V8 with an absent name. An empty string here
        // reaches the payload as "function": "", which says something where
        // nothing is meant, and disagrees with the browser, which omits the
        // field.
        StackFrames.Frame frame = StackFrames.parse("at  (/a.js:1:2)");

        Assertions.assertNotNull(frame);
        Assertions.assertEquals("/a.js:1:2", frame.location());
        Assertions.assertNull(frame.function());
    }

    @Test
    void aVirtualPathIsNotSplitAtItsAt() {
        // An @ after a slash is part of the path. These are what a dev server
        // serves, and they arrive as bare locations from the collector.
        List.of("/@fs/home/u/app/x.js:1:2", "/@id/virtual:my-mod.js:1:2",
                "/@vite/client:1:1", "webpack://@scope/pkg/./src/x.js:12:3",
                "/node_modules/@vaadin/router/router.js:12:3")
                .forEach(path -> Assertions.assertEquals(path,
                        StackFrames.location(path),
                        () -> "should survive whole: " + path));
    }

    @Test
    void aLocationCarryingCredentialsIsRefused() {
        // The one real shape the slash rule costs, and worth costing: a
        // browser does not load a subresource from a userinfo URL, and a
        // location with a password in it should not travel in a payload the
        // docs describe as meant to be forwarded.
        Assertions.assertNull(
                StackFrames.location("http://user@host/app.js:1:2"));
        Assertions.assertNull(
                StackFrames.frame("http://user:pw@host/app.js:1:2"));
        Assertions.assertNull(
                StackFrames.parse("loadData@http://user@host/app.js:1:2"));
    }

    @Test
    void aProtocolRelativeUrlIsAskedTheSameQuestionAboutCredentials() {
        // The bypass: no scheme, so a check that starts at one said "no
        // credentials here" -- and partOfPath accepts the @ because the prefix
        // begins with a slash. The password then travelled in a field that is
        // published with insights-details off.
        Assertions
                .assertNull(StackFrames.location("//user:pw@host/app.js:1:2"));
        Assertions.assertNull(StackFrames.frame("//user@host/app.js:1:2"));
        Assertions.assertNull(
                StackFrames.parse("loadData@//user:pw@host/app.js:1:2"));
        // And the same URL without them is still a location: this is a rule
        // about the authority, not about the two slashes.
        Assertions.assertEquals("//cdn.example.com/app.js:1:2",
                StackFrames.location("//cdn.example.com/app.js:1:2"));
    }

    @Test
    void aSchemeThatCarriesAPayloadIsNotALocation() {
        // data: passes every other rule -- the slash in its media type is all
        // the "path separator" test asks for -- so a few hundred bytes the
        // page chose would be published under insights-details off. blob:
        // names an object that died with the page that minted it.
        Assertions.assertNull(StackFrames
                .location("data:text/javascript;base64,YWxlcnQoMSk=:1:2"));
        Assertions.assertNull(StackFrames
                .parse("at f (data:text/javascript;base64,YWxlcnQoMSk=:1:2)"));
        Assertions.assertNull(StackFrames.location("blob:https://host/9f2c:0"));
        // A scheme is case-insensitive, and so is the rule.
        Assertions.assertNull(StackFrames
                .location("DATA:text/javascript;base64,YWxlcnQoMSk=:1:2"));
        Assertions.assertNull(StackFrames.location("Blob:https://host/9f2c:0"));
    }

    @Test
    void aLocationThatNamesThePageItselfIsNotAScriptLocation() {
        // What a browser reports for an error from an inline script, an inline
        // handler or executeJs code: the document URL, with the query string
        // that route templating exists to fold away. Published above the
        // detail gate, so the token in it would be too.
        Assertions.assertTrue(StackFrames.namesDocument(
                "https://app.example.com/orders?token=abc123:0", "/orders"));
        Assertions.assertTrue(
                StackFrames.namesDocument("/orders#section:0", "/orders"));
        Assertions.assertTrue(StackFrames.namesDocument(
                "https://app.example.com/orders:12:3", "/orders"));
        // A real script on the same page is not the page.
        Assertions.assertFalse(StackFrames.namesDocument(
                "https://app.example.com/VAADIN/build/x.js:1:2", "/orders"));
        // And nothing to compare against decides nothing.
        Assertions.assertFalse(StackFrames.namesDocument("/orders:0", null));
        Assertions.assertFalse(StackFrames.namesDocument(null, "/orders"));
    }

    @Test
    void anOverLongNumberIsNotRescuedByAppendingAColumn() {
        // The cap has to reject rather than fall back: an over-long run that
        // is merely not taken as the line number ends up inside the "file",
        // which FILE accepts, since a path really can hold digits and colons.
        Assertions.assertNull(
                StackFrames.parse("at f (/a.js:4111111111111111)"),
                "the single-number case");
        Assertions.assertNull(
                StackFrames.parse("at f (/a.js:4111111111111111:1)"),
                "and the same run with a column after it");
        Assertions.assertNull(
                StackFrames.parse("at f (/a.js:1:4111111111111111)"),
                "and in the column slot");
        Assertions.assertNull(StackFrames.location("/a.js:4111111111111111:1"),
                "source is filtered on the same terms");
        // A file may hold digits, though: a hashed bundle name is not evidence
        // of anything, so the cap is on the numeric slots alone.
        Assertions.assertEquals("chunk-1234567890123.js:1:2",
                StackFrames.location("chunk-1234567890123.js:1:2"));
    }

    @Test
    void aColonFreeMessageLineIsNotAFrame() {
        // The pass-5 defect: the first line that parses wins and a message
        // precedes the stack, so one of these does not merely add a host to
        // the insight -- it takes the place of the frame that threw.
        List.of("refused by redis@cache.internal.example:6379",
                "see bob@files.example.com/private/q3-report.pdf:1",
                "token abc 4111@evil.example.com/x.js:1:2",
                "contact admin@example.com for app.js:1:2")
                .forEach(line -> Assertions.assertNull(StackFrames.parse(line),
                        () -> "parsed as a frame: " + line));
    }

    @Test
    void aSourceIsFilteredOnTheSameTerms() {
        // `source` is published whatever the detail policy says, exactly like a
        // frame's location, so it needs the same rule -- including for the
        // shapes a browser legitimately produces for generated code.
        Assertions.assertEquals("/VAADIN/build/chart.js:44",
                StackFrames.location("/VAADIN/build/chart.js:44"));
        Assertions.assertEquals("//cdn.example.com/app.js:1:2",
                StackFrames.location("//cdn.example.com/app.js:1:2"),
                "a protocol-relative script URL is a location");
        Assertions.assertNull(
                StackFrames
                        .location("card number 4111 1111 1111 1111 declined:1"),
                "free text is not a location");
        Assertions.assertNull(StackFrames.location("/uc5"),
                "a page path with no line number is not a location");
    }

    @Test
    void theFunctionNameIsAvailableSeparatelyAndUnexamined() {
        // Not validated, deliberately: it is handed back as the browser wrote
        // it, for a caller that has been told to collect detail. The point is
        // that it is not part of the location above.
        Assertions.assertEquals("renderChart", StackFrames
                .parse("at renderChart (chart.js:44:13)").function());
        Assertions.assertEquals("async*loadData", StackFrames
                .parse("async*loadData@http://host/app.js:12:3").function());
        Assertions.assertEquals("ssn 123-45-6789 for user bob",
                StackFrames.parse("at ssn 123-45-6789 for user bob (/a.js:1:1)")
                        .function());
        Assertions.assertNull(
                StackFrames.parse("at http://host/app.js:3:7").function(),
                "an unnamed V8 frame has no name");
        Assertions.assertNull(
                StackFrames.parse("@http://host/app.js:3:7").function(),
                "nor does Firefox's bare @ form");
    }

    @Test
    void aMultiLineMessageDoesNotOutrankTheFrameBelowIt() {
        Assertions.assertNull(StackFrames.parse(
                "https://alice@files.example.com/private/q3-report.pdf:1"));
        Assertions.assertEquals("/app/upload.js:9:5",
                StackFrames.parse("at upload (/app/upload.js:9:5)").location());
    }

    @Test
    void anEnormousLineIsBoundedBeforeAnythingScansIt() {
        // The value arrives from a @ClientCallable payload, which nothing on
        // the server bounds. Unbounded, the whole-line pattern this replaced
        // spent minutes of a request thread here, or overflowed the stack.
        String hostile = "at " + "a.@".repeat(400_000) + "!";
        long start = System.nanoTime();
        StackFrames.Frame frame = StackFrames.parse(hostile);
        long ms = (System.nanoTime() - start) / 1_000_000;

        Assertions.assertNull(frame, "a megabyte of text names no location");
        Assertions.assertTrue(ms < 200,
                () -> "scanning should be linear, took " + ms + " ms");
    }

    @Test
    void aMaximalBatchOfAdversarialLinesStaysCheap() {
        // One recordSamples batch at the default client-rate-per-session, all
        // of it built to make a backtracking matcher work.
        String[] batch = new String[100];
        for (int i = 0; i < batch.length; i++) {
            StringBuilder line = new StringBuilder("x" + i);
            while (line.length() < StackFrames.MAX_LENGTH - 1) {
                line.append("a.@");
            }
            batch[i] = line.substring(0, StackFrames.MAX_LENGTH - 1) + "!";
        }

        long start = System.nanoTime();
        for (String line : batch) {
            StackFrames.parse(line);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;

        Assertions.assertTrue(ms < 100,
                () -> "a maximal batch should not cost a request thread, took "
                        + ms + " ms");
    }

    @Test
    void aLineTheBrowserVettedIsNotThrownAwayForItsLength() {
        // The two caps have to agree: the browser sends a line of up to its
        // own DETAIL_MAX, and a location is recognized by the :line that
        // truncation removes, so a line between the caps would be one the
        // browser vetted and sent and this class silently discarded.
        String tail = ":44:13)";
        String path = "/x/" + "a".repeat(StackFrames.MAX_LENGTH
                - "at f (/x/".length() - ".js".length() - tail.length())
                + ".js";
        String line = "at f (" + path + tail;

        Assertions.assertEquals(StackFrames.MAX_LENGTH, line.length(),
                "cap-length fixture");
        Assertions.assertEquals(path + ":44:13",
                StackFrames.parse(line).location(),
                "a line the browser was willing to send should be kept");
    }
}
