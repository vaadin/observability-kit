/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.lang.ref.WeakReference;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.NavigationEndedEvent;
import com.vaadin.flow.router.NavigationStartedEvent;
import com.vaadin.flow.router.NavigationTrigger;

class RequestUiTest {

    @AfterEach
    void tearDown() {
        RequestUi.clear();
        RequestInteraction.clear();
    }

    @Test
    void markedUiIsReturnedOnceAndCleared() {
        UI ui = new UI();
        RequestUi.mark(ui);
        Assertions.assertSame(ui, RequestUi.take());
        Assertions.assertNull(RequestUi.take(),
                "take consumes the slot, a second read finds nothing");
    }

    /**
     * A navigation can also be started from a background thread through
     * {@code UI.access()}, where no request interceptor ever drains the relay.
     * The relay must therefore never keep a UI reachable: a strong reference
     * would pin the UI, and through it the whole session, to a pooled executor
     * thread for the life of the server.
     */
    @Test
    void uiMarkedOutsideARequestIsNotRetained() throws InterruptedException {
        NavigationMetricsBinder binder = new NavigationMetricsBinder(
                new SimpleMeterRegistry(), new RouteTagResolver(10));

        UI ui = new UI();
        // Outside any request: nothing will call RequestUi.take() or clear()
        // on this thread afterwards.
        binder.navigationStarted(new NavigationStartedEvent(ui,
                new Location("view"), NavigationTrigger.UI_NAVIGATE));
        binder.navigationEnded(new NavigationEndedEvent(ui,
                new Location("view"), NavigationTrigger.UI_NAVIGATE,
                new NavigationEndedEvent.NotShown(), 200));

        WeakReference<UI> probe = new WeakReference<>(ui);
        ui = null;

        for (int i = 0; i < 100 && probe.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        Assertions.assertNull(probe.get(),
                "the relay must not keep a UI marked outside a request "
                        + "reachable from a pooled thread");
        Assertions.assertNull(RequestUi.take(),
                "a collected UI reads back as nothing to report");
    }

    @Test
    void markedUiIsHeldWeakly() throws InterruptedException {
        UI ui = new UI();
        RequestUi.mark(ui);
        WeakReference<UI> probe = new WeakReference<>(ui);
        ui = null;
        for (int i = 0; i < 100 && probe.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        Assertions.assertNull(probe.get(),
                "the relay alone must never keep a UI reachable");
    }

}
