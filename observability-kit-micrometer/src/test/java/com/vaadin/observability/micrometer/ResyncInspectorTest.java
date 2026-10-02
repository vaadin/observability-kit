/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import jakarta.servlet.http.HttpSession;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ResyncInspectorTest {

    private static final String LAST_CLIENT_IDS_ATTR = ResyncInspector.class
            .getName() + ".lastClientIds";

    private SimpleMeterRegistry registry;
    private ResyncInspector inspector;
    private Map<String, Object> attributes;
    private HttpSession session;
    // When set, every session read waits for the other racing request's read.
    private volatile CyclicBarrier readBarrier;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        inspector = new ResyncInspector(registry);
        attributes = new ConcurrentHashMap<>();
        session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(Mockito.anyString()))
                .thenAnswer(call -> {
                    CyclicBarrier barrier = readBarrier;
                    if (barrier != null) {
                        barrier.await(5, TimeUnit.SECONDS);
                    }
                    return attributes.get(call.<String> getArgument(0));
                });
        Mockito.doAnswer(call -> attributes.put(call.getArgument(0),
                call.getArgument(1))).when(session)
                .setAttribute(Mockito.anyString(), Mockito.any());
        Mockito.when(session.getAttributeNames()).thenAnswer(
                call -> Collections.enumeration(attributes.keySet()));
    }

    private static String message(int clientId) {
        return "{\"csrfToken\":\"x\",\"rpc\":[],\"syncId\":" + (clientId - 1)
                + ",\"clientId\":" + clientId + "}";
    }

    private void inspect(String body, String uiId) {
        inspector.inspect(body, session, uiId, session);
    }

    private double resends() {
        try {
            return registry.get(MeterNames.RESYNC)
                    .tag(MeterNames.TAG_TYPE, MeterNames.RESYNC_TYPE_RESEND)
                    .counter().count();
        } catch (MeterNotFoundException notRecorded) {
            return 0d;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, Integer> lastClientIds() {
        return (Map<Integer, Integer>) attributes.get(LAST_CLIENT_IDS_ATTR);
    }

    @Test
    void aResendIsDetectedPerUi() {
        inspect(message(0), "1");
        inspect(message(0), "2");
        inspect(message(1), "1");

        // UI 1's message 1 again: a resend. UI 2 is unaffected by UI 1.
        inspect(message(1), "1");
        inspect(message(1), "2");

        Assertions.assertEquals(1d, resends());
        Assertions.assertEquals(Map.of(1, 1, 2, 1), lastClientIds());
    }

    @Test
    void allUisShareOneSessionAttribute() {
        for (int ui = 0; ui < 10; ui++) {
            inspect(message(0), Integer.toString(ui));
        }

        Assertions.assertEquals(1, attributes.size());
        Assertions.assertEquals(10, lastClientIds().size());
    }

    @Test
    void theOldestUisAreDroppedPastTheCap() {
        int uis = ResyncInspector.MAX_TRACKED_UIS + 100;
        for (int ui = 0; ui < uis; ui++) {
            inspect(message(0), Integer.toString(ui));
        }

        Map<Integer, Integer> remembered = lastClientIds();
        Assertions.assertEquals(ResyncInspector.MAX_TRACKED_UIS,
                remembered.size());
        Assertions.assertFalse(remembered.containsKey(0));
        Assertions.assertTrue(remembered.containsKey(uis - 1));
    }

    @Test
    void anIdThatNamesNoUiIsNotRemembered() {
        inspect(message(0), "-");
        inspect(message(0), "not-a-number");
        inspect(message(0), "-5");

        Assertions.assertTrue(attributes.isEmpty());
    }

    @Test
    void theStoredMapIsUpdatedInPlaceAndSetAgain() {
        inspect(message(0), "1");
        Map<Integer, Integer> first = lastClientIds();

        inspect(message(1), "1");

        Assertions.assertSame(first, lastClientIds());
        Assertions.assertEquals(Map.of(1, 1), first);
        // Set on every update, so a replicating container sees the change.
        Mockito.verify(session, Mockito.times(2))
                .setAttribute(LAST_CLIENT_IDS_ATTR, first);
    }

    @Test
    void concurrentUisUnderDifferentLocksKeepBothClientIds() throws Exception {
        inspect(message(0), "1");
        inspect(message(0), "2");

        // Both requests read the session before either writes, and each locks
        // its own object, like a container handing out a new session wrapper
        // per request.
        readBarrier = new CyclicBarrier(2);
        ExecutorService tabs = Executors.newFixedThreadPool(2);
        try {
            Future<?> tab1 = tabs.submit(() -> inspector.inspect(message(1),
                    session, "1", new Object()));
            Future<?> tab2 = tabs.submit(() -> inspector.inspect(message(1),
                    session, "2", new Object()));
            tab1.get(10, TimeUnit.SECONDS);
            tab2.get(10, TimeUnit.SECONDS);
        } finally {
            readBarrier = null;
            tabs.shutdownNow();
        }

        Assertions.assertEquals(Map.of(1, 1, 2, 1), lastClientIds(),
                "one tab's write must not undo the other's");

        // Both tabs resend message 1: both are counted.
        inspect(message(1), "1");
        inspect(message(1), "2");
        Assertions.assertEquals(2d, resends());
    }

    @Test
    void withoutASessionNothingIsRemembered() {
        Assertions.assertDoesNotThrow(
                () -> inspector.inspect(message(0), null, "1", this));
    }
}
