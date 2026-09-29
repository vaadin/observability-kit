/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.observability.micrometer.ObservabilitySettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricsCollectorElementTest {

    /** The tasks {@code UI.access} queued, run by the test. */
    private final List<Command> accessTasks = new ArrayList<>();

    private UI ui;
    private MetricsCollectorElement element;

    @BeforeEach
    void setUp() {
        VaadinSession session = mock(VaadinSession.class, RETURNS_DEEP_STUBS);
        when(session.hasLock()).thenReturn(true);
        when(session.access(any())).thenAnswer(invocation -> {
            accessTasks.add(invocation.getArgument(0));
            return null;
        });
        ui = new UI();
        ui.getInternals().setSession(session);
        element = new MetricsCollectorElement(null,
                ObservabilitySettings.builder().build(), false);
        ui.add(element);
    }

    @Test
    void removedElementIsAddedBackToItsUi() {
        ui.remove(element);

        runAccessTasks();

        assertSame(ui, element.getParent().orElse(null));
    }

    /**
     * A resynchronization detaches and re-attaches every node in place. The
     * element is still in the UI then, so adding it again would only move it,
     * detach it once more and queue another add, forever.
     */
    @Test
    void resynchronizationDoesNotAddTheElementAgain() {
        ui.getInternals().getStateTree().prepareForResync();

        runAccessTasks();

        assertEquals(1, accessTasks.size());
        assertSame(ui, element.getParent().orElse(null));
    }

    /** Runs the queued tasks, including ones queued while running them. */
    private void runAccessTasks() {
        for (int i = 0; i < accessTasks.size() && i < 10; i++) {
            accessTasks.get(i).execute();
        }
    }
}
