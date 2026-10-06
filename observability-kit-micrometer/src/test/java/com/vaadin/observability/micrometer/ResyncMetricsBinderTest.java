/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.server.communication.ClientMessageResentEvent;
import com.vaadin.flow.server.communication.MessageIdSyncErrorEvent;
import com.vaadin.flow.server.communication.UIResynchronizationEvent;
import com.vaadin.flow.shared.Registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ResyncMetricsBinderTest {

    private final UI ui = mock(UI.class);

    private SimpleMeterRegistry registry;
    private VaadinServiceEventBus eventBus;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        eventBus = new VaadinServiceEventBus(mock(VaadinService.class));
    }

    @Test
    void everyTypeReportsZeroBeforeItsFirstEvent() {
        new ResyncMetricsBinder(registry).register(eventBus);

        for (String type : new String[] { MeterNames.RESYNC_TYPE_RESEND,
                MeterNames.RESYNC_TYPE_RESYNC,
                MeterNames.RESYNC_TYPE_OUT_OF_SYNC }) {
            assertNotNull(registry.find(MeterNames.RESYNC)
                    .tag(MeterNames.TAG_TYPE, type).counter(), type);
        }
    }

    @Test
    void eachRecoveryEventIsCountedUnderItsType() {
        new ResyncMetricsBinder(registry).register(eventBus);

        eventBus.fireEvent(new ClientMessageResentEvent(ui));
        eventBus.fireEvent(new ClientMessageResentEvent(ui));
        eventBus.fireEvent(new UIResynchronizationEvent(ui));
        eventBus.fireEvent(new MessageIdSyncErrorEvent(ui, 3, 7));

        assertEquals(2d, count(MeterNames.RESYNC_TYPE_RESEND));
        assertEquals(1d, count(MeterNames.RESYNC_TYPE_RESYNC));
        assertEquals(1d, count(MeterNames.RESYNC_TYPE_OUT_OF_SYNC));
    }

    @Test
    void removingTheRegistrationStopsCounting() {
        Registration registration = new ResyncMetricsBinder(registry)
                .register(eventBus);
        registration.remove();

        eventBus.fireEvent(new ClientMessageResentEvent(ui));
        eventBus.fireEvent(new UIResynchronizationEvent(ui));
        eventBus.fireEvent(new MessageIdSyncErrorEvent(ui, 3, 7));

        assertEquals(0d, count(MeterNames.RESYNC_TYPE_RESEND));
        assertEquals(0d, count(MeterNames.RESYNC_TYPE_RESYNC));
        assertEquals(0d, count(MeterNames.RESYNC_TYPE_OUT_OF_SYNC));
    }

    private double count(String type) {
        return registry.get(MeterNames.RESYNC).tag(MeterNames.TAG_TYPE, type)
                .counter().count();
    }
}
