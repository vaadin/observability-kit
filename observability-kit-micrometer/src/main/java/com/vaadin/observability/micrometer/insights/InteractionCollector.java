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

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.communication.RpcInvocationEvent;
import com.vaadin.flow.server.communication.RpcInvocationListener;
import com.vaadin.observability.micrometer.ComponentResolver;
import com.vaadin.observability.micrometer.ObservabilitySettings;

/**
 * Captures interesting client-to-server invocations as
 * {@link CapturedInteraction}s: failed ones (when error metrics are enabled)
 * and ones slower than the {@link #UX_BUDGET_MS UX budget} (when request
 * metrics are enabled).
 * <p>
 * Hooks Flow's {@link RpcInvocationListener} (the same hook
 * {@code RpcMetricsBinder} uses for RPC spans): {@code invocationFailed}
 * delivers the exact "user action + exception" pair, timing between
 * {@code invocationStarted} and {@code invocationEnded} gives the handling
 * duration, and the event carries the target state node from which the
 * interacted component is resolved. Works in production mode.
 */
public class InteractionCollector implements RpcInvocationListener {

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
    private static final List<String> FRAMEWORK_PREFIXES = List.of("java.",
            "jdk.", "sun.", "jakarta.", "org.springframework.", "org.apache.",
            "io.micrometer.", "com.vaadin.flow.", "com.vaadin.base.",
            "com.vaadin.observability.micrometer.",
            "com.vaadin.observability.spring.");

    private static final int STACK_TOP_FRAMES = 5;

    private final RecentInteractions buffer;
    private final boolean captureErrors;
    private final boolean captureSlow;
    private final long uxBudgetMs;

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
    }

    @Override
    public void invocationStarted(RpcInvocationEvent event) {
        // Defensively clear stale state left by an invocation whose
        // invocationEnded was skipped (e.g. mid-request server shutdown).
        errored.remove();
        componentType.set(
                ComponentResolver.resolveComponentType(event).orElse(null));
        startNanos.set(System.nanoTime());
    }

    @Override
    public void invocationFailed(RpcInvocationEvent event, Throwable error) {
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

    @Override
    public void invocationEnded(RpcInvocationEvent event) {
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

    private static CapturedInteraction errorInteraction(
            RpcInvocationEvent event, Throwable error, long durationMs,
            String component) {
        UI ui = event.getUI();
        Throwable rootCause = rootCause(error);
        StackTraceElement[] stack = rootCause.getStackTrace();
        return new CapturedInteraction(Instant.now(), route(ui), component,
                event.getName(), event.getType(),
                CapturedInteraction.OUTCOME_ERROR, durationMs,
                rootCause.getClass().getName(), rootCause.getMessage(),
                firstApplicationFrame(stack).orElse(null),
                Arrays.stream(stack).limit(STACK_TOP_FRAMES)
                        .map(StackTraceElement::toString).toList(),
                sessionId(ui), ui != null ? ui.getUIId() : -1);
    }

    private static CapturedInteraction slowInteraction(RpcInvocationEvent event,
            long durationMs, String component) {
        UI ui = event.getUI();
        return new CapturedInteraction(Instant.now(), route(ui), component,
                event.getName(), event.getType(),
                CapturedInteraction.OUTCOME_SUCCESS, durationMs, null, null,
                null, null, sessionId(ui), ui != null ? ui.getUIId() : -1);
    }

    private static String route(UI ui) {
        if (ui == null) {
            return null;
        }
        return ui.getInternals().getActiveViewLocation().getPath();
    }

    private static String sessionId(UI ui) {
        if (ui == null || ui.getSession() == null
                || ui.getSession().getSession() == null) {
            return null;
        }
        return ui.getSession().getSession().getId();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
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
