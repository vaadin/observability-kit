/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.Counter;
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
 * ({@link MeterNames#RESYNC_TYPE_OUT_OF_SYNC}). The counters are registered up
 * front, so each type reports 0 before its first event instead of no data.
 */
final class ResyncMetricsBinder {

    private final Counter resent;
    private final Counter resynchronized;
    private final Counter outOfSync;

    ResyncMetricsBinder(MeterRegistry registry) {
        this.resent = counter(registry, MeterNames.RESYNC_TYPE_RESEND);
        this.resynchronized = counter(registry, MeterNames.RESYNC_TYPE_RESYNC);
        this.outOfSync = counter(registry, MeterNames.RESYNC_TYPE_OUT_OF_SYNC);
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
        resent.increment();
    }

    void uiResynchronized(UIResynchronizationEvent event) {
        resynchronized.increment();
    }

    void messageIdSyncError(MessageIdSyncErrorEvent event) {
        outOfSync.increment();
    }

    private static Counter counter(MeterRegistry registry, String type) {
        return Counter.builder(MeterNames.RESYNC).tag(MeterNames.TAG_TYPE, type)
                .register(registry);
    }
}
