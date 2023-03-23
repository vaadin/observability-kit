package com.vaadin.observability;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Configuration object used to fine tune the front-end observability for a UI
 * instance.
 *
 * It allows to enable, disable and configure built-in client instrumentations,
 * as well as completely deactivate the functionality.
 * 
 * By default, all instrumentations are enabled.
 */
public interface ObservabilityClientConfiguration {

    /**
     * Sets if the front-end observability should be enabled for the UI
     * instance.
     * 
     * @param enabled
     *            {@literal true} to enabled collection of front-end traces,
     *            {@literal false} to disable it.
     */
    void setEnabled(boolean enabled);

    /**
     * Gets whether the front-end observability is enabled or disabled.
     * 
     * @return enabled state of the front-end observability.
     */
    boolean isEnabled();

    /**
     * Enables or disables the {@literal Document Load} instrumentation.
     *
     * @param enabled
     *            {@literal true} to enable the instrumentation,
     *            {@literal false} to disable it.
     * @see <a href=
     *      "https://github.com/open-telemetry/opentelemetry-js-contrib/tree/main/plugins/web/opentelemetry-instrumentation-document-load">
     *      OpenTelemetry Document Load Instrumentation</a>
     */
    void setDocumentLoadEnabled(boolean enabled);

    /**
     * Gets whether the {@literal Document Load} instrumentation is enabled or
     * disabled.
     *
     * @return enabled state of the {@literal Document Load} instrumentation.
     */
    boolean isDocumentLoadEnabled();

    /**
     * Enables or disables the {@literal User Interaction} instrumentation.
     *
     * @param enabled
     *            {@literal true} to enable the instrumentation,
     *            {@literal false} to disable it.
     * @see <a href=
     *      "https://github.com/open-telemetry/opentelemetry-js-contrib/tree/main/plugins/web/opentelemetry-instrumentation-user-interaction">
     *      OpenTelemetry User Interaction Instrumentation</a>
     */
    void setUserInteractionEnabled(boolean enabled);

    /**
     * Gets whether the {@literal User Interaction} instrumentation is enabled
     * or disabled.
     *
     * @return enabled state of the {@literal User Interaction} instrumentation.
     */
    boolean isUserInteractionEnabled();

    /**
     * Sets the browser events that {@literal User Interaction} instrumentation
     * should trace.
     *
     * Example of events are 'click', 'mousedown', 'touchend', 'play'.
     *
     * By default only 'click' event is instrumented.
     *
     * @param events
     *            browser events to instrument
     */
    void setUserInteractionEvents(Collection<String> events);

    /**
     * Sets the browser events that {@literal User Interaction} instrumentation
     * should trace.
     *
     * Example of events are 'click', 'mousedown', 'touchend', 'play'.
     *
     * By default only 'click' event is instrumented.
     *
     * @param events
     *            browser events to instrument
     */
    default void setUserInteractionEvents(String... events) {
        setUserInteractionEvents(Arrays.asList(events));
    }

    /**
     * Gets the set of browser events traced by {@literal User Interaction}
     * instrumentation.
     * 
     * There are no guarantees on the type, mutability, serializability, or
     * thread-safety of the Set returned.
     * 
     * @return traced browser events.
     */
    Set<String> getUserInteractionEvents();

    /**
     * Enables or disables the {@literal Long Task} instrumentation.
     *
     * @param enabled
     *            {@literal true} to enable the instrumentation,
     *            {@literal false} to disable it.
     * @see <a href=
     *      "https://github.com/open-telemetry/opentelemetry-js-contrib/tree/main/plugins/web/opentelemetry-instrumentation-long-task">
     *      OpenTelemetry Long Task Instrumentation</a>
     */
    void setLongTaskEnabled(boolean enabled);

    /**
     * Gets whether the {@literal Long Task} instrumentation is enabled or
     * disabled.
     *
     * @return enabled state of the {@literal Long Task} instrumentation.
     */
    boolean isLongTaskEnabled();

    /**
     * Enables or disables the {@literal Frontend Error} instrumentation.
     *
     * The instrumentation traces <a href=
     * "https://developer.mozilla.org/en-US/docs/Web/API/Window/error_event">error</a>
     * and <a href=
     * "https://developer.mozilla.org/en-US/docs/Web/API/Window/unhandledrejection_event">unhandled
     * rejection</a> events.
     * 
     * @param enabled
     *            {@literal true} to enable the instrumentation,
     *            {@literal false} to disable it.
     */
    void setFrontendErrorEnabled(boolean enabled);

    /**
     * Gets whether the {@literal Frontend Error} instrumentation is enabled or
     * disabled.
     *
     * @return enabled state of the {@literal Frontend Error} instrumentation.
     */
    boolean isFrontendErrorEnabled();

    /**
     * Enables or disables the {@literal XMLHttpRequest} instrumentation.
     *
     * @param enabled
     *            {@literal true} to enable the instrumentation,
     *            {@literal false} to disable it.
     * @see <a href=
     *      "https://github.com/open-telemetry/opentelemetry-js/tree/main/experimental/packages/opentelemetry-instrumentation-xml-http-request">
     *      OpenTelemetry XMLHttpRequest Instrumentation</a>
     */
    void setXMLHttpRequestEnabled(boolean enabled);

    /**
     * Gets whether the {@literal XMLHttpRequest} instrumentation is enabled or
     * disabled.
     *
     * @return enabled state of the {@literal XMLHttpRequest} instrumentation.
     */
    boolean isXMLHttpRequestEnabled();

    /**
     * Sets if requests processed by Vaadin should be ignored by the
     * {@literal XMLHttpRequest} instrumentation.
     * 
     * @param ignore
     *            {@literal true} to ignore request processed by Vaadin,
     *            {@literal false} to trace them.
     */
    void ignoreVaadinURLs(boolean ignore);

    /**
     * Gets whether the requests processed by Vaadin are ignored by the
     * {@literal XMLHttpRequest} instrumentation.
     *
     * @return {@literal true} if Vaadin requests are ignored, otherwise
     *         {@literal false}.
     */
    boolean isVaadinURLsIgnored();

    /**
     * Sets the URL patterns that the {@literal XMLHttpRequest} instrumentation
     * should ignore.
     * 
     * @param urls
     *            URL patterns to be ignored.
     */
    void setIgnoredURLs(Collection<URLPattern> urls);

    /**
     * Sets the exact match URL patterns that the {@literal XMLHttpRequest}
     * instrumentation should ignore.
     *
     * @param urls
     *            exact match URL patterns to be ignored.
     */
    default void setIgnoredURLs(String... urls) {
        setIgnoredURLs(
                Stream.of(urls).map(ObservabilityClientConfiguration::url)
                        .collect(Collectors.toList()));
    }

    /**
     * Adds an URL pattern that the {@literal XMLHttpRequest} instrumentation
     * should ignore.
     * 
     * @param url
     *            the pattern to be ignored.
     */
    void addIgnoredURL(URLPattern url);

    /**
     * Adds exact match URL patterns that the {@literal XMLHttpRequest}
     * instrumentation should ignore.
     * 
     * @param url
     *            exact match URL patterns to be ignored.
     */
    default void addIgnoredURL(String... url) {
        Stream.of(url).map(ObservabilityClientConfiguration::url)
                .forEach(this::addIgnoredURL);
    }

    /**
     * Adds exact regular expression URL patterns that the
     * {@literal XMLHttpRequest} instrumentation should ignore.
     *
     * @param pattern
     *            regular expression URL patterns to be ignored.
     */
    default void addIgnoredURLPattern(String... pattern) {
        Stream.of(pattern).map(ObservabilityClientConfiguration::urlPattern)
                .forEach(this::addIgnoredURL);
    }

    /**
     * Gets the list of URL patterns to be ignored by {@literal XMLHttpRequest}
     * instrumentation.
     * 
     * There are no guarantees on the type, mutability, serializability, or
     * thread-safety of the List returned.
     * 
     * @return list of ignored URL patterns, never {@literal null}.
     */
    List<URLPattern> getIgnoredURLs();

    /**
     * Represents a pattern matching an URL.
     * 
     * The pattern could be an exact match or a regular expression, that is
     * evaluated on the browser against a string representation of an URL.
     *
     * There is no guarantee about the form of the input URL; it may be absolute
     * or relative, depending on how a request is generated on the browser.
     * 
     * Note that the input URL may contain also the query string, so an exact
     * match pattern should also contain that part.
     *
     * Examples:
     * 
     * <pre>
     * /home                  (exact match)
     * /page?id=home          (exact match)
     * /orders/.*             (regex match)
     * /documents/.*\\.pdf    (regex match)
     * </pre>
     */
    final class URLPattern implements Serializable {
        private final String pattern;
        private final boolean exactMatch;

        /**
         * Creates an {@link URLPattern}.
         * 
         * @param pattern
         *            pattern matching the URL, either an exact match or a
         *            regular expression.
         * @param exactMatch
         *            {@literal true} if the pattern is an exact match,
         *            {@literal false} if it is a regular expression.
         */
        public URLPattern(String pattern, boolean exactMatch) {
            this.pattern = Objects.requireNonNull(pattern,
                    "pattern must not be null");
            this.exactMatch = exactMatch;
        }

        /**
         * Gets the URL pattern.
         * 
         * @return the URL pattern
         */
        public String getPattern() {
            return pattern;
        }

        /**
         * Gets whether this pattern identifies an exact match or a regular
         * expression.
         * 
         * @return {@literal true} if the pattern is an exact match, otherwise
         *         {@literal false}.
         */
        public boolean isExactMatch() {
            return exactMatch;
        }

        String toInternalFormat() {
            if (exactMatch) {
                return pattern;
            }
            return "RE:/" + pattern + "/";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            URLPattern that = (URLPattern) o;
            return exactMatch == that.exactMatch
                    && Objects.equals(pattern, that.pattern);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pattern, exactMatch);
        }

        static URLPattern fromInternalFormat(String pattern) {
            if (pattern.length() >= 5 && pattern.startsWith("RE:/")
                    && pattern.endsWith("/")) {
                new URLPattern(pattern.substring(4, pattern.length() - 2),
                        false);
            }
            return new URLPattern(pattern, true);
        }

    }

    /**
     * Creates an exact match {@link URLPattern} for the given text.
     *
     * @param url
     *            a relative or absolute URL, potentially including the query
     *            string.
     * @return an exact match {@link URLPattern} object.
     */
    static URLPattern url(String url) {
        return new URLPattern(url, true);
    }

    /**
     * Creates an {@link URLPattern} for the given regular expression.
     *
     * The input parameter must represent a valid JavaScript regular expression.
     *
     * @param regex
     *            a JavaScript regular expression.
     * @return an {@link URLPattern} object for the give regular expression.
     * @see <a href=
     *      "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide/Regular_Expressions">
     *      Regular expressions</a>
     */
    static URLPattern urlPattern(String regex) {
        return new URLPattern(regex, false);
    }

}
