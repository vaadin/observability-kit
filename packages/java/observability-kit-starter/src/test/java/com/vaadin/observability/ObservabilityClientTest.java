/*
 * Copyright (C) 2023 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 *
 */

package com.vaadin.observability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static com.vaadin.observability.ObservabilityClientConfiguration.url;
import static com.vaadin.observability.ObservabilityClientConfiguration.urlPattern;

class ObservabilityClientTest {

    private final ObservabilityClient client = new ObservabilityClient("ID");

    @Test
    void testDefaults() {
        Assertions.assertTrue(client.isEnabled(), "enabled");
        Assertions.assertTrue(client.isDocumentLoadEnabled(),
                "document load enabled");
        Assertions.assertTrue(client.isLongTaskEnabled(), "long task enabled");
        Assertions.assertTrue(client.isFrontendErrorEnabled(),
                "frontend error enabled");
        Assertions.assertTrue(client.isUserInteractionEnabled(),
                "user interaction enabled");
        Assertions.assertEquals(Set.of("click"),
                client.getUserInteractionEvents(), "user interaction events");
        Assertions.assertTrue(client.isXMLHttpRequestEnabled(),
                "xml http request enabled");
        Assertions.assertFalse(client.isIgnoreVaadinURLs(),
                "Vaadin urls ignored");
        Assertions.assertEquals(List.of(), client.getIgnoredURLs(),
                "ignored urls");
    }

    @Test
    void enabled_explicitlyDisable_getterReturnsFalse() {
        client.setEnabled(false);
        Assertions.assertFalse(client.isEnabled(),
                "Expecting enabled to be false");
    }

    @Test
    void documentLoadEnabled_explicitlyDisable_getterReturnsFalse() {
        client.setDocumentLoadEnabled(false);
        Assertions.assertFalse(client.isDocumentLoadEnabled(),
                "Expecting documentLoadEnabled to be false");
    }

    @Test
    void userInteractionEnabled_explicitlyDisable_getterReturnsFalse() {
        client.setUserInteractionEnabled(false);
        Assertions.assertFalse(client.isUserInteractionEnabled(),
                "Expecting userInteractionEnabled to be false");
    }

    @Test
    void longTaskEnabled_explicitlyDisable_getterReturnsFalse() {
        client.setLongTaskEnabled(false);
        Assertions.assertFalse(client.isLongTaskEnabled(),
                "Expecting longTaskEnabled to be false");
    }

    @Test
    void xmlHttpRequestEnabledEnabled_explicitlyDisable_getterReturnsFalse() {
        client.setXMLHttpRequestEnabled(false);
        Assertions.assertFalse(client.isXMLHttpRequestEnabled(),
                "Expecting xmlHttpRequestEnabled to be false");
    }

    @Test
    void frontendErrorEnabled_explicitlyDisable_getterReturnsFalse() {
        client.setFrontendErrorEnabled(false);
        Assertions.assertFalse(client.isFrontendErrorEnabled(),
                "Expecting frontendErrorEnabled to be false");
    }

    @Test
    void setUserInteractionEvents_eventsOverridden() {
        Assertions.assertEquals(Set.of("click"),
                client.getUserInteractionEvents(),
                "default user interaction events");

        client.setUserInteractionEvents(List.of("a", "b", "c"));
        Assertions.assertEquals(Set.of("a", "b", "c"),
                client.getUserInteractionEvents());

        client.setUserInteractionEvents("x", "y", "z");
        Assertions.assertEquals(Set.of("x", "y", "z"),
                client.getUserInteractionEvents());
    }

    @Test
    void setUserInteractionEvents_invalidInput() {
        Assertions.assertThrows(IllegalArgumentException.class,
                client::setUserInteractionEvents, "empty events varargs");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> client.setUserInteractionEvents(new ArrayList<>()),
                "empty events collection");
        Assertions.assertThrows(NullPointerException.class,
                () -> client
                        .setUserInteractionEvents((Collection<String>) null),
                "null events collection");
    }

    @Test
    void setUserInteractionEvents_ignoreNullEvents() {
        client.setUserInteractionEvents(
                Arrays.asList(null, "a", null, "b", null, "c", null));
        Assertions.assertEquals(Set.of("a", "b", "c"),
                client.getUserInteractionEvents());

        client.setUserInteractionEvents(null, "x", null, "y", null, "z", null);
        Assertions.assertEquals(Set.of("x", "y", "z"),
                client.getUserInteractionEvents());
    }

    @Test
    void ignoreVaadinURLs_explicitlyEnabled_getterReturnsTrue() {
        client.setIgnoreVaadinURLs(true);
        Assertions.assertTrue(client.isIgnoreVaadinURLs(),
                "Expecting ignoreVaadinURLs to be true");
    }

    @Test
    void setIgnoredURLs_overridesExisting() {
        client.setIgnoredURLs(List.of(url("a"), urlPattern("b"), url("c")));
        Assertions.assertEquals(List.of(url("a"), urlPattern("b"), url("c")),
                client.getIgnoredURLs());

        client.setIgnoredURLs(
                List.of(urlPattern("x"), urlPattern("y"), url("z")));
        Assertions.assertEquals(
                List.of(urlPattern("x"), urlPattern("y"), url("z")),
                client.getIgnoredURLs());
    }

    @Test
    void setIgnoredURLs_exactMatchStringList() {
        client.setIgnoredURLs("a", "b", "c");
        Assertions.assertEquals(List.of(url("a"), url("b"), url("c")),
                client.getIgnoredURLs());
    }

    @Test
    void addIgnoredURL_appendsValueToExistingList() {
        client.addIgnoredURL(url("a"));
        Assertions.assertEquals(List.of(url("a")), client.getIgnoredURLs());

        client.addIgnoredURL(urlPattern("b"));
        Assertions.assertEquals(List.of(url("a"), urlPattern("b")),
                client.getIgnoredURLs());

        client.addIgnoredURL(url("c"));
        Assertions.assertEquals(List.of(url("a"), urlPattern("b"), url("c")),
                client.getIgnoredURLs());
    }

    @Test
    void addIgnoredURL_exactMatch_appendsValueToExistingList() {
        client.addIgnoredURL("a");
        Assertions.assertEquals(List.of(url("a")), client.getIgnoredURLs());

        client.addIgnoredURL("b", "c");
        Assertions.assertEquals(List.of(url("a"), url("b"), url("c")),
                client.getIgnoredURLs());

        client.addIgnoredURL("d");
        Assertions.assertEquals(List.of(url("a"), url("b"), url("c"), url("d")),
                client.getIgnoredURLs());
    }

    @Test
    void addIgnoredURL_pattern_appendsValueToExistingList() {
        client.addIgnoredURLPattern("a");
        Assertions.assertEquals(List.of(urlPattern("a")),
                client.getIgnoredURLs());

        client.addIgnoredURLPattern("b", "c");
        Assertions.assertEquals(
                List.of(urlPattern("a"), urlPattern("b"), urlPattern("c")),
                client.getIgnoredURLs());

        client.addIgnoredURLPattern("d");
        Assertions.assertEquals(List.of(urlPattern("a"), urlPattern("b"),
                urlPattern("c"), urlPattern("d")), client.getIgnoredURLs());
    }

}
