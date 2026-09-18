/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinServiceEventBus;
import com.vaadin.flow.server.communication.AbstractRpcInvocationEvent;
import com.vaadin.flow.server.communication.RpcInvocationEndedEvent;
import com.vaadin.flow.server.communication.RpcInvocationFailedEvent;
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;
import com.vaadin.flow.shared.Registration;
import com.vaadin.observability.micrometer.ComponentResolver;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.RouteTagResolver;

/**
 * Captures interesting client-to-server invocations as
 * {@link CapturedInteraction}s: failed ones (when error metrics are enabled)
 * and ones slower than the {@link #UX_BUDGET_MS UX budget} (when request
 * metrics are enabled).
 * <p>
 * Listens for the RPC invocation events on the service event bus, the same
 * events {@code RpcMetricsBinder} uses for RPC spans:
 * {@link RpcInvocationFailedEvent} delivers the exact "user action + exception"
 * pair, timing between {@link RpcInvocationStartedEvent} and
 * {@link RpcInvocationEndedEvent} gives the handling duration, and the events
 * carry the target state node from which the interacted component is resolved.
 * Works in production mode.
 * <p>
 * In development mode it additionally reads what is on the screen: the caption
 * of the interacted component, and the {@link ViewState values its view was
 * holding}, so that the {@code replay} of an insight names the button the
 * reader is looking for and states what the failure needed to be set. Neither
 * is collected in production, where the payload is meant to be forwarded and
 * both would carry application text — see {@link ComponentCaptions}.
 */
public class InteractionCollector {

    /**
     * Absolute per-interaction latency budget for good UX, in milliseconds.
     * Beyond roughly one second a user loses the feeling of operating directly
     * on the UI, so interactions over this budget are captured as interactions
     * regardless of any historical baseline.
     */
    public static final long UX_BUDGET_MS = 1000;

    /**
     * Stack frames from these packages are infrastructure, not application
     * code; the first frame NOT matching a prefix is reported as the likely bug
     * location.
     */
    private static final List<String> FRAMEWORK_PREFIXES = List.of(
            // JDK and language runtimes
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "kotlin.", "scala.",
            // Vaadin and this kit
            "com.vaadin.flow.", "com.vaadin.base.", "com.vaadin.hilla.",
            "com.vaadin.observability.micrometer.",
            "com.vaadin.observability.spring.",
            // Servers, frameworks and persistence
            "org.springframework.", "org.apache.", "org.atmosphere.",
            "org.hibernate.", "org.eclipse.", "io.micrometer.",
            // Proxy and bytecode generators, whose synthetic frames would
            // otherwise be reported as the application's own code
            "net.bytebuddy.", "org.objenesis.", "javassist.", "org.javassist.");

    private static final int STACK_TOP_FRAMES = 5;

    private final RecentInteractions buffer;
    private final boolean captureErrors;
    private final boolean captureSlow;
    private final long uxBudgetMs;
    private final boolean details;
    /**
     * Whether the captions and values on the screen may be read. Development
     * mode only: they are application text that a forwarded payload should not
     * carry, and the reader who benefits is the developer running the
     * application.
     */
    private final boolean screenDetail;
    private final RouteTagResolver routes;

    private final ThreadLocal<Long> startNanos = new ThreadLocal<>();
    private final ThreadLocal<Boolean> errored = ThreadLocal
            .withInitial(() -> Boolean.FALSE);
    /**
     * Resolved at {@code invocationStarted}: the handler may detach the target
     * node (e.g. a Grid component column refreshing its item), so resolving at
     * {@code invocationEnded} would come up empty.
     * <p>
     * The component itself is held, not just its class name, because the value
     * a field ends up with can only be read once the invocation has run. It is
     * cleared at {@code invocationEnded} along with the rest, so no UI is
     * pinned by a thread between requests.
     */
    private final ThreadLocal<Component> target = new ThreadLocal<>();

    /**
     * @param buffer
     *            where captured interactions are retained
     * @param settings
     *            the kit's settings, for which interactions to capture and how
     *            much detail they may carry
     * @param developmentMode
     *            whether the application runs in development mode, which is
     *            where component captions and the state of the view are
     *            collected
     */
    public InteractionCollector(RecentInteractions buffer,
            ObservabilitySettings settings, boolean developmentMode) {
        this(buffer, settings, developmentMode, UX_BUDGET_MS);
    }

    /**
     * Test seam allowing the slow-interaction threshold to be overridden so
     * timing behaviour can be exercised without real delays.
     */
    InteractionCollector(RecentInteractions buffer,
            ObservabilitySettings settings, boolean developmentMode,
            long uxBudgetMs) {
        this.buffer = buffer;
        this.captureErrors = settings.isErrors();
        this.captureSlow = settings.isRequests();
        this.uxBudgetMs = uxBudgetMs;
        this.details = settings.isInsightsDetails();
        this.screenDetail = developmentMode;
        this.routes = new RouteTagResolver(settings.getRouteCardinalityLimit());
    }

    /**
     * Subscribes to the RPC invocation events on the given bus.
     *
     * @param eventBus
     *            the service event bus to listen on
     * @return a handle removing every subscription made here
     */
    public Registration register(VaadinServiceEventBus eventBus) {
        return Registration.combine(
                eventBus.addListener(RpcInvocationStartedEvent.class,
                        this::invocationStarted),
                eventBus.addListener(RpcInvocationFailedEvent.class,
                        this::invocationFailed),
                eventBus.addListener(RpcInvocationEndedEvent.class,
                        this::invocationEnded));
    }

    void invocationStarted(RpcInvocationStartedEvent event) {
        // Defensively clear stale state left by an invocation whose
        // invocationEnded was skipped (e.g. mid-request server shutdown).
        errored.remove();
        Component component = ComponentResolver.resolveComponent(event)
                .orElse(null);
        target.set(component);
        rememberIfField(event.getUI(), component);
        startNanos.set(System.nanoTime());
    }

    /**
     * Notes that the user has worked a field, which is what later lets a replay
     * report the values they set and leave out the ones the view came with.
     * Only the component's identity is kept; its value is read when an
     * interaction is captured.
     */
    private void rememberIfField(UI ui, Component component) {
        if (!screenDetail || !ComponentCaptions.holdsValue(component)) {
            return;
        }
        TouchedFields touched = TouchedFields.of(ui);
        if (touched != null) {
            touched.add(component);
        }
    }

    void invocationFailed(RpcInvocationFailedEvent event) {
        Throwable error = event.getError();
        errored.set(Boolean.TRUE);
        if (!captureErrors) {
            return;
        }
        try {
            buffer.add(
                    errorInteraction(event, error, elapsedMs(), target.get()));
        } catch (RuntimeException e) {
            // Collection is best-effort enrichment; never interfere with the
            // framework's own error handling.
        }
    }

    void invocationEnded(RpcInvocationEndedEvent event) {
        long durationMs = elapsedMs();
        Component component = target.get();
        startNanos.remove();
        target.remove();
        boolean failed = errored.get();
        errored.remove();
        // Failed invocations are already captured with their duration; only
        // successful-but-slow ones are captured here.
        if (!failed && captureSlow && durationMs >= uxBudgetMs) {
            try {
                buffer.add(slowInteraction(event, durationMs, component));
            } catch (RuntimeException e) {
                // Best-effort: collection never interferes with the request.
            }
        }
    }

    /**
     * The values the view was holding, in the modes where the screen is read.
     * Read now rather than accumulated as the user worked, so that what an
     * insight reports is the state this interaction actually ran against.
     */
    private List<ComponentState> viewState(UI ui, Component component) {
        return screenDetail ? ViewState.of(ui, component, TouchedFields.of(ui))
                : List.of();
    }

    /** The component's caption, in the modes where captions are read. */
    private String caption(Component component) {
        return screenDetail ? ComponentCaptions.captionOf(component) : null;
    }

    private long elapsedMs() {
        Long start = startNanos.get();
        if (start == null) {
            return -1;
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private CapturedInteraction errorInteraction(
            AbstractRpcInvocationEvent event, Throwable error, long durationMs,
            Component component) {
        UI ui = event.getUI();
        Throwable rootCause = Throwables.rootCause(error);
        StackTraceElement[] stack = rootCause.getStackTrace();
        // The exception type and the first application frame are always kept:
        // they are what makes the insight actionable and neither is free-form
        // user data. The message and the remaining frames are withheld unless
        // the application opted in.
        return new CapturedInteraction(Instant.now(), route(ui), location(ui),
                typeOf(component), caption(component), event.getName(),
                event.getType(), viewState(ui, component),
                CapturedInteraction.OUTCOME_ERROR, durationMs, -1, details,
                rootCause.getClass().getName(),
                details ? InsightDetails
                        .truncate(rootCause.getMessage()) : null,
                firstApplicationFrame(stack).orElse(null),
                details ? Arrays.stream(stack).limit(STACK_TOP_FRAMES)
                        .map(StackTraceElement::toString).toList() : null,
                InsightDetails.sessionId(ui, details),
                ui != null ? ui.getUIId() : -1);
    }

    private CapturedInteraction slowInteraction(
            AbstractRpcInvocationEvent event, long durationMs,
            Component component) {
        UI ui = event.getUI();
        // The budget this interaction was actually measured against travels
        // with it, so a report never has to assume the default.
        return new CapturedInteraction(Instant.now(), route(ui), location(ui),
                typeOf(component), caption(component), event.getName(),
                event.getType(), viewState(ui, component),
                CapturedInteraction.OUTCOME_SUCCESS, durationMs, uxBudgetMs,
                details, null, null, null, null,
                InsightDetails.sessionId(ui, details),
                ui != null ? ui.getUIId() : -1);
    }

    private static String typeOf(Component component) {
        return component == null ? null : component.getClass().getName();
    }

    /**
     * The route <em>template</em> of the active view, so that {@code orders/17}
     * and {@code orders/18} group under one {@code orders/:orderId} insight
     * instead of one per parameter value. Falls back to the concrete location
     * when no navigation target can be resolved; both paths go through
     * {@link RouteTagResolver}, so the number of distinct values stays bounded
     * either way.
     */
    private String route(UI ui) {
        return ui == null ? null : routes.tagForActiveRoute(ui);
    }

    /** The concrete location, reported per example rather than grouped on. */
    private static String location(UI ui) {
        return ui == null ? null
                : ui.getInternals().getActiveViewLocation().getPath();
    }

    private static Optional<String> firstApplicationFrame(
            StackTraceElement[] stack) {
        return Arrays.stream(stack)
                .filter(frame -> FRAMEWORK_PREFIXES.stream().noneMatch(
                        prefix -> frame.getClassName().startsWith(prefix)))
                .findFirst().map(StackTraceElement::toString);
    }

}
