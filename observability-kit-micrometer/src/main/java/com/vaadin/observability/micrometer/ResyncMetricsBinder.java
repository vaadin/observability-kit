/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.MeterRegistry;

import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.server.communication.ClientMessageResentEvent;
import com.vaadin.flow.server.communication.MessageIdSyncErrorEvent;
import com.vaadin.flow.server.communication.UIResynchronizationEvent;
import com.vaadin.flow.shared.Registration;

/**
 * Counts client message recovery from the events on the
 * {@link com.vaadin.flow.server.VaadinService#getEventBus() service event bus}
 * into {@link MeterNames#RESYNC}.
 * <p>
 * Flow fires the events while handling the client's message ids, for both HTTP
 * and push requests: a re-sent message it answers with the previous response
 * ({@link MeterNames#RESYNC_TYPE_RESEND}), a resynchronization the client asked
 * for ({@link MeterNames#RESYNC_TYPE_RESYNC}), and an unexpected message id
 * that shows the user the session synchronization error
 * ({@link MeterNames#RESYNC_TYPE_OUT_OF_SYNC}).
 */
final class ResyncMetricsBinder {

    private final MeterRegistry registry;

    ResyncMetricsBinder(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Subscribes to the message recovery events on the given bus.
     *
     * @param eventBus
     *            the service event bus to listen on
     * @return a handle removing every subscription made here
     */
    Registration register(VaadinServiceEventBus eventBus) {
        return Registration.combine(
                eventBus.addListener(ClientMessageResentEvent.class,
                        this::messageResent),
                eventBus.addListener(UIResynchronizationEvent.class,
                        this::uiResynchronized),
                eventBus.addListener(MessageIdSyncErrorEvent.class,
                        this::messageIdSyncError));
    }

    void messageResent(ClientMessageResentEvent event) {
        count(MeterNames.RESYNC_TYPE_RESEND);
    }

    void uiResynchronized(UIResynchronizationEvent event) {
        count(MeterNames.RESYNC_TYPE_RESYNC);
    }

    void messageIdSyncError(MessageIdSyncErrorEvent event) {
        count(MeterNames.RESYNC_TYPE_OUT_OF_SYNC);
    }

    private void count(String type) {
        registry.counter(MeterNames.RESYNC, MeterNames.TAG_TYPE, type)
                .increment();
    }
}
