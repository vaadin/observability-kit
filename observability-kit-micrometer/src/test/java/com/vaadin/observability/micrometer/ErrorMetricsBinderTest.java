/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

/**
 * Verifies that failures Flow routes to the session {@code ErrorHandler} — the
 * ones a user triggers, which never reach a request interceptor — are counted
 * and attributed.
 */
class ErrorMetricsBinderTest {

    @Tag("test-button")
    private static class TestButton extends Component {
    }

    @Tag("ambient-view")
    private static class AmbientView extends Component {
    }

    /** Error handler that only remembers what it was handed. */
    private static final class RecordingHandler implements ErrorHandler {
        final List<Throwable> seen = new ArrayList<>();

        @Override
        public void error(ErrorEvent event) {
            seen.add(event.getThrowable());
        }
    }

    private SimpleMeterRegistry registry;
    private ErrorMetricsBinder binder;
    private AtomicReference<ErrorHandler> handler;
    private VaadinSession session;
    private RecordingHandler application;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        binder = new ErrorMetricsBinder(new ErrorCounter(registry,
                ObservabilitySettings.builder().build()));
        application = new RecordingHandler();
        handler = new AtomicReference<>(application);
        session = Mockito.mock(VaadinSession.class);
        Mockito.when(session.getErrorHandler()).thenAnswer(i -> handler.get());
        Mockito.doAnswer(i -> {
            handler.set(i.getArgument(0));
            return null;
        }).when(session).setErrorHandler(Mockito.any());
    }

    @AfterEach
    void tearDown() {
        CurrentInstance.clearAll();
        RequestError.clear();
    }

    private void sessionInit() {
        binder.sessionInit(
                new SessionInitEvent(Mockito.mock(VaadinService.class), session,
                        Mockito.mock(VaadinRequest.class)));
    }

    /** An error event carrying the state node of an attached component. */
    private static ErrorEvent errorFor(Throwable error) {
        UI ui = new UI();
        TestButton button = new TestButton();
        ui.getElement().appendChild(button.getElement());
        StateNode node = button.getElement().getNode();
        return new ErrorEvent(error, node);
    }

    private Counter errorCounter(String exception, String component) {
        return registry.find(MeterNames.ERRORS)
                .tag(MeterNames.TAG_EXCEPTION, exception)
                .tag(MeterNames.TAG_COMPONENT, component).counter();
    }

    @Test
    void listenerFailureIsCountedAndTaggedByComponent() {
        sessionInit();

        handler.get().error(errorFor(new IllegalStateException("boom")));

        Counter counter = errorCounter("IllegalStateException", "TestButton");
        Assertions.assertNotNull(counter,
                "a failure handled by the session error handler must be counted");
        Assertions.assertEquals(1.0, counter.count(), 0.0);
    }

    @Test
    void failureWithoutAComponentStillCarriesTheTagKeys() {
        // Prometheus rejects same-named meters whose tag-key sets differ, so
        // an error with no resolvable component must still emit every key.
        sessionInit();

        handler.get().error(new ErrorEvent(new IllegalArgumentException("x")));

        Counter counter = errorCounter("IllegalArgumentException",
                MeterNames.COMPONENT_UNKNOWN);
        Assertions.assertNotNull(counter,
                "the component tag must be present, bucketed as unknown");
        Assertions.assertEquals(MeterNames.ROUTE_UNKNOWN,
                counter.getId().getTag(MeterNames.TAG_ROUTE),
                "the route tag must be present too");
    }

    @Test
    void theRouteComesFromTheComponentsOwnUiNotTheAmbientOne() {
        // UI.getCurrent() is ambient state and can name a different UI of the
        // session than the one the error was thrown for, so the component's
        // own UI wins. Here the component sits in a UI that has never
        // navigated (root location, ""), while the ambient UI would resolve to
        // AmbientView.
        UI componentUi = new UI();
        TestButton button = new TestButton();
        componentUi.getElement().appendChild(button.getElement());
        UI ambient = Mockito.mock(UI.class, RETURNS_DEEP_STUBS);
        Mockito.when(ambient.getInternals().getActiveRouterTargetsChain())
                .thenReturn(List.of(new AmbientView()));
        CurrentInstance.set(UI.class, ambient);

        sessionInit();
        handler.get().error(new ErrorEvent(new IllegalStateException("boom"),
                button.getElement().getNode()));

        Counter counter = errorCounter("IllegalStateException", "TestButton");
        Assertions.assertNotNull(counter);
        Assertions.assertEquals("",
                counter.getId().getTag(MeterNames.TAG_ROUTE),
                "the route must come from the UI the component is attached to");
    }

    @Test
    void theApplicationHandlerStillSeesTheError() {
        sessionInit();
        IllegalStateException error = new IllegalStateException("boom");

        handler.get().error(errorFor(error));

        Assertions.assertEquals(List.of(error), application.seen,
                "instrumentation must delegate, not replace");
    }

    @Test
    void instrumentingTwiceDoesNotCountTwice() {
        sessionInit();
        ErrorHandler afterFirst = handler.get();
        sessionInit();

        Assertions.assertSame(afterFirst, handler.get(),
                "an already instrumented handler must be left alone");
        handler.get().error(errorFor(new IllegalStateException("boom")));
        Assertions.assertEquals(1.0,
                errorCounter("IllegalStateException", "TestButton").count(),
                0.0);
    }

    @Test
    void aReplacedHandlerIsReinstrumentedOnTheNextInvocation() {
        sessionInit();
        // An application that installs its own handler after session init
        // would otherwise silently switch error metrics off.
        RecordingHandler replacement = new RecordingHandler();
        session.setErrorHandler(replacement);

        binder.invocationStarted(rpcEvent());

        IllegalStateException error = new IllegalStateException("boom");
        handler.get().error(errorFor(error));

        Assertions.assertEquals(1.0,
                errorCounter("IllegalStateException", "TestButton").count(),
                0.0);
        Assertions.assertEquals(List.of(error), replacement.seen,
                "the application's own handler must keep receiving errors");
    }

    @Test
    void aReplacedHandlerIsReinstrumentedAtUiInit() {
        // An application that installs its handler from a UIInitListener of
        // its own would otherwise lose the whole bootstrap request: UI init
        // still runs while that request is being handled, so re-instrumenting
        // there covers the beforeEnter callbacks and beforeClientResponse
        // executions that follow, instead of waiting for the first RPC.
        sessionInit();
        RecordingHandler replacement = new RecordingHandler();
        session.setErrorHandler(replacement);

        UI ui = Mockito.mock(UI.class);
        Mockito.when(ui.getSession()).thenReturn(session);
        binder.uiInit(new UIInitEvent(ui, Mockito.mock(VaadinService.class)));

        IllegalStateException error = new IllegalStateException("boom");
        handler.get().error(errorFor(error));

        Assertions.assertEquals(1.0,
                errorCounter("IllegalStateException", "TestButton").count(),
                0.0);
        Assertions.assertEquals(List.of(error), replacement.seen,
                "the application's own handler must keep receiving errors");
    }

    @Test
    void chainedInstrumentedHandlersCountTheFailureOnce() {
        sessionInit();
        // An application handler that delegates to the handler it replaced
        // leaves our wrapper in the chain; re-instrumenting on top of it must
        // not turn one failure into two.
        ErrorHandler instrumented = handler.get();
        session.setErrorHandler(event -> instrumented.error(event));
        binder.invocationStarted(rpcEvent());

        handler.get().error(errorFor(new IllegalStateException("boom")));

        Assertions.assertEquals(1.0,
                errorCounter("IllegalStateException", "TestButton").count(),
                0.0);
    }

    @Test
    void anExceptionThatEscapedRequestHandlingIsCountedOnce() {
        // Flow's handleExceptionDuringRequest notifies the request
        // interceptors and then the session error handler with the same
        // throwable; that is one failure, not two.
        RequestMetricsBinder requests = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        sessionInit();
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        VaadinResponse response = Mockito.mock(VaadinResponse.class);
        IllegalStateException error = new IllegalStateException("boom");

        requests.requestStart(request, response);
        requests.handleException(request, response, session, error);
        handler.get().error(new ErrorEvent(error));
        requests.requestEnd(request, response, session);

        Assertions.assertEquals(1.0,
                registry.find(MeterNames.ERRORS)
                        .tag(MeterNames.TAG_EXCEPTION, "IllegalStateException")
                        .counter().count(),
                0.0, "the same throwable must only be counted once");
    }

    @Test
    void aHandledFailureMakesTheRequestOutcomeAnError() {
        // The interaction the request carried failed, so the request did too —
        // even though Flow handled the exception and the interceptor never saw
        // it.
        RequestMetricsBinder requests = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        sessionInit();
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        VaadinResponse response = Mockito.mock(VaadinResponse.class);
        CurrentInstance.set(VaadinRequest.class, request);

        requests.requestStart(request, response);
        handler.get().error(errorFor(new IllegalStateException("boom")));
        requests.requestEnd(request, response, session);

        Timer timer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_ERROR).timer();
        Assertions.assertNotNull(timer,
                "a request whose interaction failed must not report success");
        Assertions.assertEquals(1L, timer.count());
    }

    @Test
    void aHandledFailureOutsideARequestDoesNotLeakIntoTheNextRequest() {
        // No request is current (a UI.access body failing on a background
        // thread), so nothing may be relayed to whatever request this pooled
        // thread serves next.
        RequestMetricsBinder requests = new RequestMetricsBinder(registry,
                ObservabilitySettings.builder().traces(false).build());
        sessionInit();
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        VaadinResponse response = Mockito.mock(VaadinResponse.class);

        handler.get().error(errorFor(new IllegalStateException("boom")));

        requests.requestStart(request, response);
        requests.requestEnd(request, response, session);

        Timer timer = registry.find(MeterNames.REQUEST_DURATION)
                .tag(MeterNames.TAG_OUTCOME, MeterNames.OUTCOME_SUCCESS)
                .timer();
        Assertions.assertNotNull(timer);
        Assertions.assertEquals(1L, timer.count(),
                "the background failure must not mark this request as failed");
    }

    private RpcInvocationStartedEvent rpcEvent() {
        UI ui = Mockito.mock(UI.class);
        Mockito.when(ui.getSession()).thenReturn(session);
        RpcInvocationStartedEvent event = Mockito
                .mock(RpcInvocationStartedEvent.class);
        Mockito.when(event.getUI()).thenReturn(ui);
        return event;
    }
}
