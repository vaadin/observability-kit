/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.vaadin.observability.micrometer.ObservabilitySettings;

/**
 * Pulls the script location out of a line a browser reported, and hands back
 * nothing at all when the line does not name one.
 * <p>
 * This is a filter on untrusted text. What it returns is retained and published
 * whatever {@link ObservabilitySettings#isInsightsDetails()} says, so the only
 * thing it may return is a location — and a browser offers plenty that is not
 * one. The first line of a V8 stack is the error's message; a message can span
 * lines and a continuation line can look like a URL; {@code stack} on a
 * rejection reason is whatever the rejecting code put there.
 * <p>
 * <b>Split structurally, then validate the location.</b> A single regex over
 * the whole line cannot do this: the free text and the location sit in one
 * expression, so tightening one end loosens the other, and the alternation
 * needed to cover both engines backtracks super-quadratically on text designed
 * to fail. Splitting on the separators the engines actually emit —
 * {@code " ("}…{@code ")"} for V8, {@code @} for SpiderMonkey and JSC — puts
 * the location in a slot of its own, where linear scans and one unambiguous
 * character class settle it.
 * <p>
 * <b>The function name is not validated, because it cannot be.</b> A frame's
 * name comes from the function's {@code name} property, and
 * {@code Object.defineProperty(f, 'name', …)} takes any string at all — V8
 * prints whatever it is told. Filtering an attacker-chosen string down to
 * "shapes a language produces" is open-ended: every rule that admits
 * {@code Object.<anonymous>}, {@code new Foo}, {@code async Foo.bar},
 * {@code Array.map [as forEach]} and Firefox's {@code promise callback*fn} also
 * admits some arrangement of words that is not a name. So the name is not part
 * of what this class returns as a location. It is available separately, and the
 * caller gates it like any other text the page chose.
 * <p>
 * What a location <em>is</em>, precisely: a file that looks like a file, and a
 * line number. It is still text the page determined — a script URL can carry a
 * query string, and this class does not try to tell a real one from a crafted
 * one — so a location is bounded and shaped, not secret-free. That is the
 * boundary the field's whole purpose requires: an insight that cannot say where
 * the code is says nothing worth reading.
 * <p>
 * Kept in step with the same rules in {@code VaadinMetricsClient.js}, which
 * applies them in the browser to spare the buffer and the wire. That copy
 * decides nothing: any script on the page can call the {@code @ClientCallable}
 * directly, so this class is the only rule a crafted payload meets. The corpus
 * both are checked against is one shared file, {@code stack-frames-corpus.tsv}.
 */
final class StackFrames {

    /**
     * Longest text kept. Bounded <em>before</em> anything scans it: the value
     * arrives from a {@code @ClientCallable} payload, which nothing on the
     * server bounds — the browser's cap is a courtesy, and
     * {@code ClientRateLimiter} counts samples rather than bytes.
     * <p>
     * The same number as the in-browser collector's {@code DETAIL_MAX}, and it
     * has to stay that way: a location is recognized by the {@code :line} that
     * truncation removes, so a line between the two caps would be one the
     * browser vetted and sent and this class then silently discarded.
     */
    static final int MAX_LENGTH = 400;

    /**
     * Longest run of digits a line or column number may be. A real one is a
     * position in a file; a longer one is a number that wanted to be published,
     * and the two numeric slots are the parts of a location narrow enough to
     * say so. The <em>file</em> is not: a bundle really can be called
     * {@code chunk-1234567890123.js}, so digits there are not evidence of
     * anything.
     */
    private static final int MAX_NUMBER_DIGITS = 8;

    /** {@link #numberStart} found no {@code :digits} run at all. */
    private static final int NO_NUMBER = -1;

    /**
     * {@link #numberStart} found a {@code :digits} run longer than a position
     * in a file. Distinct from {@link #NO_NUMBER} because the two have to be
     * handled differently: a location legitimately has no column, but a slot
     * that failed the cap must reject the whole text rather than be folded back
     * into the file — {@link #FILE} admits digits and colons, so
     * {@code /a.js:4111111111111111:1} would otherwise pass with the over-long
     * run sitting inside its "file".
     */
    private static final int OVER_LONG = -2;

    /** V8 opens every frame with this. */
    private static final String AT = "at ";

    /** V8 wraps the location of a named frame in these. */
    private static final String OPEN = " (";

    /**
     * The characters a file path or URL is made of: the unreserved and reserved
     * sets of RFC 3986, plus the backslash of a Windows path, the braces and
     * pipe a bundler puts in a virtual path, and any letter or digit so a
     * non-ASCII path segment or an internationalized host survives.
     * <p>
     * A positive class, and deliberately not {@code \S}: that is ASCII-only in
     * Java and Unicode-aware in JavaScript, so the two copies of this rule
     * would disagree, with the server — the only one that matters — the more
     * permissive of them. {@code UNICODE_CHARACTER_CLASS} would not settle it
     * either, since U+200B and U+FEFF are not {@code White_Space} in either
     * engine. Note what is <em>absent</em>: whitespace of every kind, and the
     * parentheses that are stripped structurally before this is applied.
     */
    private static final Pattern FILE = Pattern
            .compile("[\\p{L}\\p{N}._~:/?#!$&'*+,;=%@\\[\\]{}|\\\\-]+");

    /**
     * Whether the text opens with a URL scheme and its slashes —
     * {@code https:}, {@code webpack:}. What tells a path prefix from a
     * function name, together with a leading slash.
     * <p>
     * A character scan rather than a pattern, to stay line-for-line comparable
     * with the browser's copy, which cannot use one: the loader strips comments
     * from that file before injecting it, and its parser reads the double slash
     * inside a regex literal as the start of a line comment — which truncated
     * the file at that line and took the whole collector with it.
     */
    private static boolean hasScheme(String text) {
        int colon = text.indexOf(':');
        if (colon < 1 || colon + 2 >= text.length()
                || text.charAt(colon + 1) != '/'
                || text.charAt(colon + 2) != '/') {
            return false;
        }
        for (int at = 0; at < colon; at++) {
            char c = text.charAt(at);
            boolean alpha = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            boolean extra = at > 0 && ((c >= '0' && c <= '9') || c == '+'
                    || c == '.' || c == '-');
            if (!alpha && !extra) {
                return false;
            }
        }
        return true;
    }

    /**
     * What a whole {@code name@location} line may be made of: the {@link #FILE}
     * characters plus the angle brackets Firefox writes into a nested-function
     * name ({@code name/<@…}).
     * <p>
     * This is the structural rule the {@code @} form needs and V8's does not.
     * V8 delimits its location — {@code at }, or {@code " ("}…{@code ")"} — so
     * the split point is the engine's choice. The {@code @} form has no
     * delimiter: the split lands wherever an {@code @} happens to be, which
     * means a line that is not a frame gets cut into a "name" and something
     * that passes for a location. Requiring the <em>whole line</em> to be built
     * from these characters is what separates a frame line from a sentence,
     * because a sentence has spaces in it and none of these is one.
     * <p>
     * The cost, deliberately paid: Safari's {@code global code@…},
     * {@code module code@…} and {@code eval code@…}, and Firefox's
     * space-containing async markers ({@code promise callback*fn@…},
     * {@code setTimeout handler*fn@…}), are no longer read as frames.
     * {@code firstFrame} moves to the next line, and where such a line is the
     * only one in the stack the insight falls back to {@code source}, which for
     * a top-level script error the browser supplies. The alternative was an
     * allowlist of engine-generated names, and four rounds of rules about what
     * may stand in front of the {@code @} is enough: whitespace or not is a
     * property of the line, not a judgement about a name.
     */
    private static final Pattern AT_FORM_LINE = Pattern
            .compile("[\\p{L}\\p{N}._~:/?#!$&'*+,;=%@\\[\\]{}|\\\\<>-]+");

    /**
     * Schemes whose "path" is a payload rather than a place, and so are not
     * locations however well shaped they are.
     * <p>
     * A {@code data:} URL's body is a few hundred bytes the page chose, and it
     * passes every other rule without trying —
     * {@code data:text/javascript;base64,AAAA:1:2} has the slash and the
     * character set {@link #FILE} admits, so the cap on the text is the only
     * thing standing between it and the payload. A {@code blob:} URL names an
     * object that died with the page that made it, so it locates nothing anyone
     * can open. Both are published whatever
     * {@link ObservabilitySettings#isInsightsDetails()} says.
     * <p>
     * Matched by prefix rather than through {@link #hasScheme}, which knows
     * only the {@code scheme://authority} form: neither of these has an
     * authority of its own.
     */
    private static final List<String> OPAQUE_SCHEMES = List.of("data:",
            "blob:");

    /**
     * One stack line, taken apart.
     *
     * @param location
     *            the script location, validated — the only part of a frame that
     *            is published unconditionally
     * @param function
     *            the function name exactly as the browser wrote it, or
     *            {@code null} when the frame named none. Unvalidated, because a
     *            page can set it to anything; the caller has to treat it as
     *            text the page chose
     */
    record Frame(String location, @Nullable String function) {
    }

    private StackFrames() {
    }

    /**
     * One stack line taken apart, or {@code null} when it names no location.
     *
     * @param reported
     *            one line of a stack as the browser wrote it, may be
     *            {@code null}
     * @return the frame, or {@code null} when the line is not one
     */
    static @Nullable Frame parse(@Nullable String reported) {
        String line = bounded(reported);
        if (line == null) {
            return null;
        }
        String function;
        String location;
        if (line.startsWith(AT)) {
            String rest = line.substring(AT.length());
            int open = rest.lastIndexOf(OPEN);
            if (open >= 0 && rest.endsWith(")")) {
                function = rest.substring(0, open);
                location = rest.substring(open + OPEN.length(),
                        rest.length() - 1);
            } else {
                // V8's unnamed frame: "at file:line:col".
                function = null;
                location = rest;
            }
        } else {
            // No delimiter in this form, so the line as a whole has to look
            // like one before its @ may be treated as a separator at all. See
            // AT_FORM_LINE: without this, any sentence containing an @ splits
            // into a "name" and something that passes for a location, and
            // since firstFrame takes the first line that parses and a message
            // precedes the stack, the insight loses the real frame as well as
            // gaining a host.
            if (!AT_FORM_LINE.matcher(line).matches()) {
                return null;
            }
            // Firefox, SpiderMonkey and JSC all write file:line:column here.
            // Requiring both numbers is the second half of telling a frame
            // line from a URL that merely ends in a number -- a message
            // quoting "…/q3-report.pdf:1" has one.
            if (!hasLineAndColumn(line)) {
                return null;
            }
            // The first @ that is not part of a path -- see separatorIn. An @
            // after a slash belongs to a scoped package or a dev server's
            // virtual path, so splitting there would cut a path in half.
            // Firefox writes a bare @ for a top-level frame, which leaves the
            // name empty.
            int at = separatorIn(line);
            if (at < 0) {
                return null;
            }
            function = at == 0 ? null : line.substring(0, at);
            location = line.substring(at + 1);
            // A colon before the @ means the split landed inside a URL rather
            // than at a name boundary: no name an engine writes has a colon in
            // it.
            if (function != null && function.indexOf(':') >= 0) {
                return null;
            }
        }
        if (!isLocation(location)) {
            return null;
        }
        // "at (/a.js:1:2)" -- a V8 frame whose name is absent rather than
        // empty. An empty string here would reach the payload as
        // "function": "", which says something where nothing is meant.
        return new Frame(location,
                function == null || function.isBlank() ? null : function);
    }

    /**
     * What a report's {@code frame} field names, accepting either shape it can
     * arrive in: a bare location, which is what the in-browser collector sends
     * now that it does the stack walking itself, or a whole stack line, which
     * is what a crafted payload sends.
     * <p>
     * <b>The two branches are disjoint, and that is what makes this safe — not
     * the order.</b> {@link #separatorIn} is the reason: a location may contain
     * no {@code @} that fails to follow a {@code /}, and {@link #parse}'s
     * {@code @} form splits on precisely such an {@code @}, so no text
     * satisfies both. V8's form cannot be a location either, since it carries
     * spaces and parentheses. A location is asked for first only because it is
     * the shape the collector actually sends.
     * <p>
     * It was not always disjoint, and the history is worth keeping. While an
     * {@code @} after a slash still counted as a separator,
     * {@code /node_modules/@vaadin/router/router.js:12:3} parsed as a frame
     * called {@code /node_modules/} in a file called
     * {@code vaadin/router/router.js} — so asking {@code parse} first mangled
     * most of the component library into a path that does not exist. Loosening
     * {@code separatorIn} would make the order load-bearing again.
     * <p>
     * The bare-location branch is the same trust level as {@code source}: a
     * location is page-determined text that has to look like a location, no
     * more. What it is <em>not</em> is a stack line, so nothing is split out of
     * it. That matters because only the browser ever sees the stack, and so
     * only {@link #parse} — running there, over each line — can tell a frame
     * from the continuation line of a multi-line message. A payload that skips
     * the browser skips that, exactly as it skips every other client-side rule.
     *
     * @param reported
     *            the {@code frame} the report carried, may be {@code null}
     * @return the frame, or {@code null} when it names no location
     */
    static @Nullable Frame frame(@Nullable String reported) {
        String location = location(reported);
        if (location != null) {
            return new Frame(location, null);
        }
        return parse(reported);
    }

    /**
     * The text, bounded, when it is a script location — {@code file:line} or
     * {@code file:line:col}; {@code null} otherwise.
     * <p>
     * Applied to the {@code source} a browser reports for an error, which is
     * published on the same terms as a frame's location and so needs the same
     * filter.
     *
     * @param reported
     *            a location as the browser wrote it, may be {@code null}
     * @return the location to retain, or {@code null} when it is not one
     */
    static @Nullable String location(@Nullable String reported) {
        String text = bounded(reported);
        return text != null && isLocation(text) ? text : null;
    }

    private static @Nullable String bounded(@Nullable String reported) {
        if (reported == null) {
            return null;
        }
        String trimmed = reported.trim();
        return trimmed.isEmpty() ? null
                : InsightDetails.truncate(trimmed, MAX_LENGTH);
    }

    /**
     * Whether the text is {@code file:line} or {@code file:line:col} with a
     * file that looks like one. Parsed from the end by hand rather than by
     * regex: the file may contain colons and digits of its own — every
     * {@code http://} does — so a pattern would have to guess where the file
     * stops, and guessing is what makes one backtrack.
     */
    private static boolean isLocation(String text) {
        return fileOf(text) != null;
    }

    /**
     * The file part of a location — everything before its {@code :line} and
     * optional {@code :col} — or {@code null} when the text is not a location
     * at all. {@link #isLocation} is this question asked without the answer,
     * and {@link #namesDocument} is the one caller that needs the file itself.
     */
    private static @Nullable String fileOf(String text) {
        int lastNumber = numberStart(text, text.length());
        if (lastNumber < 0) {
            return null;
        }
        int firstNumber = numberStart(text, lastNumber);
        // An over-long run has to fail the location, not fall through to being
        // part of the file: appending a column is otherwise all it takes to
        // get an arbitrary number published inside the "file".
        if (firstNumber == OVER_LONG) {
            return null;
        }
        String file = text.substring(0,
                firstNumber == NO_NUMBER ? lastNumber : firstNumber);
        // The file may not itself end in ":digits". A location has a line and
        // at most a column and nothing after them, so this bounds the numeric
        // slots to the two that were just checked rather than to the last two
        // of however many there are. Without it a third number pushes an
        // over-long run back into the "file", where FILE accepts it because a
        // path really can hold digits and colons -- and the cap then bounds
        // nothing. A port survives: "http://host:8080/app.js" does not end in
        // a number.
        if (numberStart(file, file.length()) != NO_NUMBER) {
            return null;
        }
        // A location names a file, and a file has an extension or a path
        // separator. Without this, any "word:1" would be one.
        boolean location = (file.indexOf('.') >= 0 || file.indexOf('/') >= 0)
                && FILE.matcher(file).matches() && separatorIn(file) < 0
                && !hasUserInfo(file) && !hasOpaqueScheme(file);
        return location ? file : null;
    }

    /** Whether the file opens with one of the {@link #OPAQUE_SCHEMES}. */
    private static boolean hasOpaqueScheme(String file) {
        for (String scheme : OPAQUE_SCHEMES) {
            // Case-insensitively, because a scheme is: "DATA:" names the same
            // thing. ASCII case folding either way — none of these letters has
            // a locale that moves it.
            if (file.regionMatches(true, 0, scheme, 0, scheme.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a location names the page's own document rather than a script.
     * <p>
     * For an error thrown from an inline script, an inline event handler or
     * {@code Page.executeJs} code, a browser reports the <em>document</em> URL
     * as the error's {@code filename} and writes it into the stack frame. That
     * is not where the code is, and unlike a script URL it carries the page's
     * query string — a token, an order id — which would then be published with
     * {@link ObservabilitySettings#isInsightsDetails()} off, and would split
     * one finding into one per parameter value besides.
     * <p>
     * Compared on the path alone, so the query string that is the point of the
     * rule cannot evade it, and against the path the report itself carried:
     * that is the page the browser was on, and the only page this server knows
     * the report to be about. The in-browser collector drops the plain case
     * before it sends; this is the same rule where a crafted payload, or a
     * stack frame rather than a {@code filename}, also reaches.
     *
     * @param location
     *            a location, as {@link #location} returned it, may be
     *            {@code null}
     * @param pagePath
     *            the path the browser reported being on, may be {@code null}
     * @return {@code true} when the location names that page
     */
    static boolean namesDocument(@Nullable String location,
            @Nullable String pagePath) {
        if (location == null || pagePath == null || pagePath.isBlank()) {
            return false;
        }
        String file = fileOf(location);
        return file != null && pathOf(file).equals(pagePath);
    }

    /**
     * The path of a file: its authority and its scheme taken off the front, its
     * query and fragment off the end. What is left is what a browser's
     * {@code location.pathname} reports for the same URL.
     */
    private static String pathOf(String file) {
        String path = file;
        int authority = -1;
        if (hasScheme(path)) {
            authority = path.indexOf(':') + 3;
        } else if (path.startsWith("//")) {
            authority = 2;
        }
        if (authority >= 0) {
            int slash = path.indexOf('/', authority);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        int query = path.indexOf('?');
        int fragment = path.indexOf('#');
        int cut = query < 0 ? fragment
                : (fragment < 0 ? query : Math.min(query, fragment));
        return cut < 0 ? path : path.substring(0, cut);
    }

    /**
     * The index of the first {@code @} in the text that is <em>not</em> part of
     * a path, or {@code -1} when every {@code @} in it is; that is, the index
     * at which an engine's {@code name@location} splits.
     * <p>
     * An {@code @} preceded by {@code /} belongs to the path: that is what a
     * scoped npm package is ({@code /node_modules/@vaadin/router/router.js}),
     * and what a dev server's virtual paths are ({@code /@fs/…},
     * {@code /@id/…}, {@code /@vite/client},
     * {@code webpack://@scope/pkg/./src/x.js}). Anything else is a name glued
     * to a location, which is either an engine's separator or — read as a
     * location — a path nobody can open.
     * <p>
     * Used both ways round, and that is the point. {@link #isLocation} requires
     * there to be no such {@code @}, so a whole {@code name@file:line:col} line
     * is not mistaken for a location and published as one; {@link #parse} takes
     * it as the split point, so the same line is taken apart properly, with the
     * path in {@code frame} and the name in the gated field. Between them a
     * value that used to be published raw is now canonicalised.
     * <p>
     * The one shape this refuses that a browser might not: a URL carrying
     * credentials, {@code http://user@host/app.js:1:2}. No loss worth keeping —
     * browsers do not load subresources from userinfo URLs, and a location with
     * a password in it is the last thing that should travel in a forwarded
     * payload. And one it refuses that is merely unlikely: an {@code @}
     * mid-filename, as in the {@code chunk@2x.js} retina convention, which is
     * an image naming habit rather than a script one.
     */
    private static int separatorIn(String text) {
        for (int at = text.indexOf('@'); at >= 0; at = text.indexOf('@',
                at + 1)) {
            if (!partOfPath(text, at)) {
                return at;
            }
        }
        return -1;
    }

    /**
     * Whether the {@code @} at {@code at} belongs to the path rather than
     * separating a name from one.
     * <p>
     * The question is asked of the text <em>before</em> the {@code @}, not of
     * the single character in front of it. "The previous character is a slash"
     * covers a scoped package at the start of a path segment
     * ({@code /npm/@vaadin/…}) and nothing else — so it rejected every
     * version-pinned CDN URL, {@code …/@vaadin/router@1.7.5/dist/router.js},
     * where the second {@code @} follows a letter. An app served from jsDelivr,
     * unpkg or esm.sh, or resolving through an import map, lost {@code frame}
     * and {@code source} for every browser error it reported.
     * <p>
     * What separates the two is that a path prefix is <em>rooted or
     * schemed</em>: {@code /node_modules/…}, {@code https://…},
     * {@code webpack://…}. A function name is neither, and this is deliberately
     * narrower than "the prefix contains a slash" — Firefox writes a nested
     * function as {@code outer/inner} and an anonymous one as {@code outer/<},
     * so a slash alone would make those look like paths and publish the name
     * glued to the location.
     * <p>
     * The leading {@code @} is its own case. Firefox writes one for a frame
     * with no function, so it is a separator when a location plainly follows —
     * an absolute path or a URL with a scheme. When anything else follows it
     * opens a scoped bare specifier, {@code @vaadin/grid/x.js}, and eating the
     * {@code @} would name a package that does not exist.
     */
    private static boolean partOfPath(String text, int at) {
        if (at == 0) {
            String rest = text.substring(1);
            return !(rest.startsWith("/") || hasScheme(rest));
        }
        String prefix = text.substring(0, at);
        return prefix.indexOf('/') >= 0
                && (prefix.charAt(0) == '/' || hasScheme(prefix));
    }

    /**
     * Whether a URL carries credentials — an {@code @} inside its authority,
     * with something in front of it. Kept as a rule of its own rather than
     * folded into {@link #partOfPath}, because a version pin and a password
     * share nothing but the character: one is in the path, the other between
     * the scheme and the first slash.
     * <p>
     * Refused because a location with a password in it is the last thing that
     * should travel in a payload meant to be forwarded, and because a browser
     * does not load subresources from userinfo URLs, so nothing real is lost.
     * The empty-userinfo form is not that: {@code webpack://@scope/pkg/…} puts
     * a scoped package where an authority would go, and has to survive.
     * <p>
     * A protocol-relative URL — {@code //user:pw@host/app.js:1:2} — is the same
     * shape with the scheme left out, and has to be asked the same question. It
     * has none, so a check that starts at one answered {@code false} and let
     * the password through {@link #partOfPath}, which accepts the {@code @}
     * because the prefix begins with a slash.
     */
    private static boolean hasUserInfo(String file) {
        int start;
        if (hasScheme(file)) {
            // Past the colon and its two slashes, up to the next one.
            start = file.indexOf(':') + 3;
        } else if (file.startsWith("//")) {
            // Protocol-relative: the authority starts after the slashes.
            start = 2;
        } else {
            return false;
        }
        int slash = file.indexOf('/', start);
        String authority = slash < 0 ? file.substring(start)
                : file.substring(start, slash);
        return authority.indexOf('@') > 0;
    }

    /** Whether the text ends in both a line and a column number. */
    private static boolean hasLineAndColumn(String text) {
        int column = numberStart(text, text.length());
        return column >= 0 && numberStart(text, column) >= 0;
    }

    /**
     * The index of the {@code :} introducing the run of digits that ends at
     * {@code end}; {@link #NO_NUMBER} when there is no such run, and
     * {@link #OVER_LONG} when the run is longer than a position in a file ever
     * is.
     * <p>
     * ASCII {@code 0}–{@code 9} explicitly, not {@link Character#isDigit},
     * which admits every Unicode decimal digit — Arabic-Indic among them —
     * where the browser's copy of this scan reads character codes and does not.
     * That would make the server the more permissive of the two again, which is
     * the whole thing {@link #FILE} exists to avoid.
     */
    private static int numberStart(String text, int end) {
        int at = end;
        while (at > 0 && text.charAt(at - 1) >= '0'
                && text.charAt(at - 1) <= '9') {
            at--;
            if (end - at > MAX_NUMBER_DIGITS) {
                return OVER_LONG;
            }
        }
        return at < end && at > 0 && text.charAt(at - 1) == ':' ? at - 1
                : NO_NUMBER;
    }
}
