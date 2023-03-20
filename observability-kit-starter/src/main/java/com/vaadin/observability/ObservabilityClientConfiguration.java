package com.vaadin.observability;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

/**
 * Configuration is used to fine tune the front-end observability for a UI
 * instance.
 *
 * It allows to activate or deactivate and configure built-in client
 * instrumentations, as well as completely disable the functionality.
 */
public interface ObservabilityClientConfiguration {

    /**
     * Defines if the front-end observability should be enabled for the UI
     * instance.
     * 
     * @param active
     *            {@literal true} to activate collection of front-end traces,
     *            {@literal false} to disable it.
     */
    void active(boolean active);

    /**
     * Toggles the activation of the DocumentLoad instrumentation.
     *
     * @param active
     */
    void traceDocumentLoad(boolean active);

    /**
     * Toggles state of UserInteraction instrumentation.
     * 
     * @param active
     */
    void traceUserInteraction(boolean active);

    void traceUserInteraction(Set<String> events);

    void traceLongTask(boolean active);

    void traceXMLHttpRequests(boolean active);

    void ignoreVaadinUrls(boolean active);

    void ignoreUrls(Collection<String> urls);

    default void ignoreUrls(String... urls) {
        ignoreUrls(Arrays.asList(urls));
    }

    void ignoreUrlsByPattern(Collection<String> pattern);

    default void ignoreUrlsByPattern(String... urlRegex) {
        ignoreUrlsByPattern(Arrays.asList(urlRegex));
    }

    void traceErrors(boolean active);
}
