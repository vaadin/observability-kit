/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
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
 * {@link RpcInvocationFailedEvent} delivers the exact "user action +
 * exception" pair, timing between {@link RpcInvocationStartedEvent} and
 * {@link RpcInvocationEndedEvent} gives the handling duration, and the events
 * carry the target state node from which the interacted component is
 * resolved. Works in production mode.
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

    /**
     * Exception messages are truncated to this many characters even when detail
     * is enabled: a message is free-form text and can carry a whole payload.
     */
    private static final int MAX_MESSAGE_LENGTH = 200;

    /** Hex characters kept of the hashed session id. */
    private static final int SESSION_HASH_LENGTH = 12;

    private final RecentInteractions buffer;
    private final boolean captureErrors;
    private final boolean captureSlow;
    private final long uxBudgetMs;
    private final boolean details;
    private final RouteTagResolver routes;

    private final ThreadLocal<Long> startNanos = new ThreadLocal<>();
    private final ThreadLocal<Boolean> errored = ThreadLocal
            .withInitial(() -> Boolean.FALSE);
    /**
     * Resolved at {@code invocationStarted}: the handler may detach the target
     * node (e.g. a Grid component column refreshing its item), so resolving at
     * {@code invocationEnded} would come up empty.
     */
    private final ThreadLocal<String> componentType = new ThreadLocal<>();

    public InteractionCollector(RecentInteractions buffer,
            ObservabilitySettings settings) {
        this(buffer, settings, UX_BUDGET_MS);
    }

    /**
     * Test seam allowing the slow-interaction threshold to be overridden so
     * timing behaviour can be exercised without real delays.
     */
    InteractionCollector(RecentInteractions buffer,
            ObservabilitySettings settings, long uxBudgetMs) {
        this.buffer = buffer;
        this.captureErrors = settings.isErrors();
        this.captureSlow = settings.isRequests();
        this.uxBudgetMs = uxBudgetMs;
        this.details = settings.isInsightsDetails();
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

    public void invocationStarted(RpcInvocationStartedEvent event) {
        // Defensively clear stale state left by an invocation whose
        // invocationEnded was skipped (e.g. mid-request server shutdown).
        errored.remove();
        componentType.set(
                ComponentResolver.resolveComponentType(event).orElse(null));
        startNanos.set(System.nanoTime());
    }

    public void invocationFailed(RpcInvocationFailedEvent event) {
        Throwable error = event.getError();
        errored.set(Boolean.TRUE);
        if (!captureErrors) {
            return;
        }
        try {
            buffer.add(errorInteraction(event, error, elapsedMs(),
                    componentType.get()));
        } catch (RuntimeException e) {
            // Collection is best-effort enrichment; never interfere with the
            // framework's own error handling.
        }
    }

    public void invocationEnded(RpcInvocationEndedEvent event) {
        long durationMs = elapsedMs();
        String component = componentType.get();
        startNanos.remove();
        componentType.remove();
        boolean failed = errored.get();
        errored.remove();
        // Failed invocations are already captured with their duration; only
        // successful-but-slow ones are captured here.
        if (failed || !captureSlow || durationMs < uxBudgetMs) {
            return;
        }
        try {
            buffer.add(slowInteraction(event, durationMs, component));
        } catch (RuntimeException e) {
            // Best-effort, as above.
        }
    }

    private long elapsedMs() {
        Long start = startNanos.get();
        if (start == null) {
            return -1;
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private CapturedInteraction errorInteraction(
            AbstractRpcInvocationEvent event,
            Throwable error, long durationMs, String component) {
        UI ui = event.getUI();
        Throwable rootCause = rootCause(error);
        StackTraceElement[] stack = rootCause.getStackTrace();
        // The exception type and the first application frame are always kept:
        // they are what makes the insight actionable and neither is free-form
        // user data. The message and the remaining frames are withheld unless
        // the application opted in.
        return new CapturedInteraction(Instant.now(), route(ui), location(ui),
                component, event.getName(), event.getType(),
                CapturedInteraction.OUTCOME_ERROR, durationMs, -1, details,
                rootCause.getClass().getName(),
                details ? truncate(rootCause.getMessage()) : null,
                firstApplicationFrame(stack).orElse(null),
                details ? Arrays.stream(stack).limit(STACK_TOP_FRAMES)
                        .map(StackTraceElement::toString).toList() : null,
                sessionId(ui), ui != null ? ui.getUIId() : -1);
    }

    private CapturedInteraction slowInteraction(
            AbstractRpcInvocationEvent event,
            long durationMs, String component) {
        UI ui = event.getUI();
        // The budget this interaction was actually measured against travels
        // with it, so a report never has to assume the default.
        return new CapturedInteraction(Instant.now(), route(ui), location(ui),
                component, event.getName(), event.getType(),
                CapturedInteraction.OUTCOME_SUCCESS, durationMs, uxBudgetMs,
                details, null, null, null, null, sessionId(ui),
                ui != null ? ui.getUIId() : -1);
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
        if (ui == null) {
            return null;
        }
        for (HasElement target : ui.getInternals()
                .getActiveRouterTargetsChain()) {
            if (target instanceof Component component) {
                return routes.tagFor(component.getClass());
            }
        }
        return routes.tagForTemplate(location(ui));
    }

    /** The concrete location, reported per example rather than grouped on. */
    private static String location(UI ui) {
        return ui == null ? null
                : ui.getInternals().getActiveViewLocation().getPath();
    }

    /**
     * The session id, reduced to a short one-way hash unless detail is enabled.
     * The hash still correlates the examples of one insight while not being an
     * identifier that could be replayed against the running application.
     */
    private String sessionId(UI ui) {
        if (ui == null || ui.getSession() == null
                || ui.getSession().getSession() == null) {
            return null;
        }
        String id = ui.getSession().getSession().getId();
        if (id == null) {
            return null;
        }
        return details ? id : hash(id);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0,
                    SESSION_HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; never fall back to the raw id.
            return null;
        }
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH) + "…";
    }

    /**
     * Walks to the root cause, tracking the causes already visited so a cyclic
     * causal chain terminates. A cycle longer than a self-reference (A caused
     * by B caused by A) would otherwise spin forever, and this runs while the
     * server is already handling a failure.
     *
     * @return the root cause, or the last cause before the chain looped back
     */
    private static Throwable rootCause(Throwable error) {
        Set<Throwable> visited = new HashSet<>();
        Throwable cause = error;
        visited.add(cause);
        while (cause.getCause() != null && visited.add(cause.getCause())) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Optional<String> firstApplicationFrame(
            StackTraceElement[] stack) {
        return Arrays.stream(stack)
                .filter(frame -> FRAMEWORK_PREFIXES.stream().noneMatch(
                        prefix -> frame.getClassName().startsWith(prefix)))
                .findFirst().map(StackTraceElement::toString);
    }

}
