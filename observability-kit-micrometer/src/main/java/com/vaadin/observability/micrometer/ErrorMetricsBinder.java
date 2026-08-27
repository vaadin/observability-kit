/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.SessionInitListener;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;
import com.vaadin.flow.shared.Registration;

/**
 * Counts the failures a user actually experiences, by observing the session
 * {@link ErrorHandler}.
 * <p>
 * Only exceptions that escape request handling reach a
 * {@code VaadinRequestInterceptor}. Everything a user can trigger — a component
 * listener that throws, a {@code UI.access} body, a detach listener, a
 * {@code beforeEnter} callback — is caught by Flow and routed to
 * {@code VaadinSession.getErrorHandler()} instead, so those failures never
 * reached {@link MeterNames#ERRORS} and left the enclosing
 * {@code vaadin.request} span reporting {@code outcome=success}. The
 * {@link ErrorEvent} additionally carries the state node the failure happened
 * for, which is what lets the counter be tagged by component and route rather
 * than by exception class alone.
 * <p>
 * The session's handler is decorated (never replaced) at session init, and the
 * decoration is re-applied at UI init and on the
 * {@link RpcInvocationStartedEvent}: applications commonly install their own
 * error handler from a {@code SessionInitListener}/{@code UI} of their own, and
 * whichever ran last would otherwise silently switch error metrics off.
 * Decorating is idempotent and the wrapper always delegates, so an
 * application's own handler keeps seeing every error it saw before.
 */
final class ErrorMetricsBinder implements SessionInitListener, UIInitListener {

    private static final Logger LOGGER = LoggerFactory
            .getLogger(ErrorMetricsBinder.class);

    private final ErrorCounter errors;

    /**
     * @param errors
     *            the counter to record into, shared with the request
     *            interceptor so both writers of {@link MeterNames#ERRORS} draw
     *            on one cardinality budget
     */
    ErrorMetricsBinder(ErrorCounter errors) {
        this.errors = errors;
    }

    /**
     * Subscribes to the RPC invocation start event on the given bus.
     *
     * @param eventBus
     *            the service event bus to listen on
     * @return a handle removing the subscription made here
     */
    Registration register(VaadinServiceEventBus eventBus) {
        return eventBus.addListener(RpcInvocationStartedEvent.class,
                this::invocationStarted);
    }

    @Override
    public void sessionInit(SessionInitEvent event) {
        instrument(event.getSession());
    }

    @Override
    public void uiInit(UIInitEvent event) {
        UI ui = event.getUI();
        if (ui != null) {
            instrument(ui.getSession());
        }
    }

    void invocationStarted(RpcInvocationStartedEvent event) {
        UI ui = event.getUI();
        if (ui != null) {
            instrument(ui.getSession());
        }
    }

    /**
     * Wraps the session's error handler unless it is already wrapped. Requires
     * the session lock, which all three call sites hold.
     */
    private void instrument(VaadinSession session) {
        if (session == null) {
            return;
        }
        try {
            ErrorHandler current = session.getErrorHandler();
            if (current instanceof InstrumentedErrorHandler instrumented) {
                if (instrumented.isRecording()) {
                    return;
                }
                // A deserialized session carries a wrapper that lost its
                // (non-serializable) counter; wrap its delegate afresh.
                current = instrumented.delegate;
            }
            session.setErrorHandler(
                    new InstrumentedErrorHandler(current, errors));
        } catch (RuntimeException | AssertionError e) {
            // AssertionError too: VaadinSession's getErrorHandler and
            // setErrorHandler check the session lock with an assert, so a
            // future call site that runs without the lock would otherwise
            // break session init rather than just lose a measurement.
            LOGGER.debug("Could not instrument the session error handler; "
                    + "errors handled by it will not be counted.", e);
        }
    }

    /**
     * Counts the error, tells the enclosing request that it failed, and hands
     * the event on unchanged.
     */
    private static final class InstrumentedErrorHandler
            implements ErrorHandler {

        /**
         * Guards against counting one error twice when several wrappers end up
         * chained — an application handler that itself delegates to a handler
         * we wrapped earlier, or a nested error while handling an error.
         */
        private static final ThreadLocal<Boolean> HANDLING = new ThreadLocal<>();

        private final ErrorHandler delegate;
        /**
         * The meter registry behind this is not serializable, so the counter
         * cannot travel with a serialized session; {@link #isRecording()}
         * reports whether it survived.
         */
        private final transient ErrorCounter errors;

        InstrumentedErrorHandler(ErrorHandler delegate, ErrorCounter errors) {
            this.delegate = delegate;
            this.errors = errors;
        }

        boolean isRecording() {
            return errors != null;
        }

        @Override
        public void error(ErrorEvent event) {
            if (Boolean.TRUE.equals(HANDLING.get())) {
                delegate.error(event);
                return;
            }
            HANDLING.set(Boolean.TRUE);
            try {
                record(event);
                delegate.error(event);
            } finally {
                HANDLING.remove();
            }
        }

        private void record(ErrorEvent event) {
            if (errors == null) {
                return;
            }
            try {
                Throwable error = event.getThrowable();
                // Flow reports an exception that escaped request handling to
                // the request interceptors and to this handler; the
                // interceptor got there first, so only relay the outcome.
                if (!RequestError.isCounted(error)) {
                    errors.increment(error, event.getComponent().orElse(null));
                }
                RequestError.markHandled(error);
            } catch (RuntimeException | AssertionError e) {
                // Instrumentation must never keep the application's own error
                // handling from running — not even when a Flow assertion about
                // the session lock fails on the thread that failed.
                LOGGER.debug("Could not record a handled error.", e);
            }
        }
    }
}
