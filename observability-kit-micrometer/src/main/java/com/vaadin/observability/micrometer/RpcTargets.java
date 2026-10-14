/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.Optional;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.server.communication.AbstractRpcInvocationEvent;

/**
 * Finds the component an RPC invocation targets. Shared by the instrumentation
 * that enriches spans and the insights collector that records interactions, so
 * both name the same component.
 */
public final class RpcTargets {

    private RpcTargets() {
    }

    /**
     * The component the invocation targets, unwrapped to the innermost one when
     * it is a {@code Composite}: the event names the outermost composite mapped
     * to the target element, which has no caption and holds no value, while the
     * field it wraps does. Returns {@link Optional#empty()} if the invocation
     * targets no component or it cannot be looked up. The lookup is best-effort
     * enrichment and never throws.
     * <p>
     * Callers must not keep the returned instance beyond the invocation being
     * handled: it is a live component of a live UI, and holding it would pin
     * the whole UI.
     *
     * @param event
     *            the RPC invocation to find the target component for
     * @return the component, or empty
     */
    public static Optional<Component> componentOf(
            AbstractRpcInvocationEvent event) {
        try {
            return event.getComponent()
                    .map(component -> component instanceof Composite<?> c
                            ? ComponentUtil.getInnermostComponent(c)
                            : component);
        } catch (RuntimeException e) {
            // Never let the lookup break the invocation or its span.
            return Optional.empty();
        }
    }
}
