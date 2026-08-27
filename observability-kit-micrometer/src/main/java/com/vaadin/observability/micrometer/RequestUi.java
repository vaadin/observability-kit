/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import com.vaadin.flow.component.UI;

/**
 * Thread-local relay carrying the UI the in-flight UIDL request is handled for,
 * from the binders that see it during handling (RPC invocations, navigation,
 * polls) to {@code RequestMetricsBinder#requestEnd}, which resolves the active
 * route from it.
 * <p>
 * The relay exists because {@link UI#getCurrent()} is only bound while the UIDL
 * handler processes the request inside the session lock, and is cleared again
 * before the request interceptors' {@code requestEnd} runs. UIDL processing is
 * synchronous on the request thread, so a UI marked during handling is visible
 * to {@code requestEnd} on the same thread. The interceptor clears the slot at
 * {@code requestStart} and consumes it at {@code requestEnd}, so a pooled
 * thread never leaks a UI reference across requests.
 */
final class RequestUi {

    private static final ThreadLocal<UI> CURRENT = new ThreadLocal<>();

    private RequestUi() {
    }

    /**
     * Records the UI the current request is being handled for. Last writer
     * wins; every marker within one request sees the same UI.
     */
    static void mark(UI ui) {
        if (ui != null) {
            CURRENT.set(ui);
        }
    }

    /**
     * Returns and clears the UI for the current thread, or {@code null} if none
     * was marked.
     */
    static UI take() {
        UI value = CURRENT.get();
        CURRENT.remove();
        return value;
    }

    /**
     * Clears any value left over from a previous request on this (pooled)
     * thread.
     */
    static void clear() {
        CURRENT.remove();
    }
}
