/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.LoggerFactory;

/**
 * Helpers for the {@link Observation.Scope} thread-locals held by the binders.
 * <p>
 * Every method here is deliberately conservative about
 * {@link Observation.Scope#close()}: closing a scope ends by reinstating the
 * scope that was current when it was opened. For a scope that leaked from a
 * finished request that predecessor is dead, so closing it out of turn would
 * evict a live enclosing scope (the Spring/Boot HTTP observation, for instance)
 * and re-parent the rest of the request onto a stale span. A scope is therefore
 * only ever closed while it is the registry's current one.
 */
final class ObservationScopes {

    private ObservationScopes() {
    }

    /**
     * Discards a scope left behind by a previous request or invocation whose
     * end callback never ran (e.g. mid-request server shutdown), closing it
     * only if it is still the registry's current scope.
     * <p>
     * Dropping the binder's own reference is not enough: the scope also lives
     * in the {@code ObservationRegistry}'s own thread-local, so on a pooled
     * thread the stale observation would stay "current" and the next
     * observation started on that thread would be parented onto it. When the
     * stale scope is still current, closing it unwinds that thread-local to
     * whatever was current before it was opened. When it is not current
     * something live sits above it, and closing it would evict that live scope,
     * so the reference is only dropped.
     *
     * @param registry
     *            the registry the scope was opened against, may be {@code null}
     * @param holder
     *            the thread-local holding the possibly stale scope
     */
    static void closeStale(ObservationRegistry registry,
            ThreadLocal<Observation.Scope> holder) {
        Observation.Scope stale = holder.get();
        holder.remove();
        if (stale == null || registry == null) {
            return;
        }
        if (registry.getCurrentObservationScope() != stale) {
            // Something live is current above the stale scope; unwinding now
            // would evict it, so the reference is only dropped. That leaves a
            // known corner case: when the live scope above closes it restores
            // the stale one as current, and with the holder already cleared
            // nothing closes it afterwards, so work started on this thread
            // outside a Vaadin request (the next HTTP request, an actuator
            // scrape) is parented on the dead observation until the next
            // requestStart clears it. Micrometer offers no way to remove a
            // scope from the middle of the chain, and reaching this branch at
            // all needs an end callback to have been skipped, so the trade is
            // deliberate: a rare stale parent outside a request beats
            // corrupting the parenting of every live one.
            return;
        }
        closeQuietly(stale);
    }

    /**
     * Closes {@code scope} at the end of the request or invocation that opened
     * it, first unwinding any scope that nested instrumentation left current on
     * top of it.
     * <p>
     * Without that unwinding a leaked nested scope (an RPC invocation whose end
     * callback never ran, say) would never see {@code onScopeClosed}, leaving
     * handler-side state such as the OpenTelemetry context or the logging MDC
     * pointing at a dead observation for whatever runs on this pooled thread
     * next — including {@code ContextSnapshot.captureAll()} in
     * {@code TracingExecutor}.
     * <p>
     * If {@code scope} is not on the registry's current chain at all it is
     * assumed to have been unwound already and nothing is closed.
     *
     * @param registry
     *            the registry the scope was opened against, may be {@code null}
     * @param scope
     *            the scope to close, may be {@code null}
     */
    static void closeWithNested(ObservationRegistry registry,
            Observation.Scope scope) {
        if (scope == null) {
            return;
        }
        if (registry == null) {
            closeQuietly(scope);
            return;
        }
        // Count how many scopes sit above ours before touching any of them, so
        // that a scope which is not on the chain at all is left alone.
        int nested = 0;
        Observation.Scope walk = registry.getCurrentObservationScope();
        while (walk != null && walk != scope) {
            nested++;
            walk = walk.getPreviousObservationScope();
        }
        if (walk != scope) {
            // Our scope is not on this thread's chain: it was already unwound,
            // or the chain was replaced wholesale.
            return;
        }
        for (int i = 0; i < nested; i++) {
            Observation.Scope leaked = registry.getCurrentObservationScope();
            if (leaked == null || leaked == scope) {
                break;
            }
            closeQuietly(leaked);
        }
        closeQuietly(scope);
    }

    private static void closeQuietly(Observation.Scope scope) {
        try {
            scope.close();
        } catch (RuntimeException e) {
            // A handler failing while unwinding a scope must not fail the
            // request. The binder's thread-local is already cleared by the
            // time we get here.
            LoggerFactory.getLogger(ObservationScopes.class)
                    .debug("Failed to close an observation scope", e);
        }
    }
}
