/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.lang.ref.WeakReference;

import com.vaadin.flow.component.UI;

/**
 * Thread-local relay carrying the UI the in-flight UIDL request is handled for,
 * from the binders that see it during handling (RPC invocations, navigation,
 * polls) to {@code RequestMetricsBinder#requestEnded}, which resolves the
 * active route from it.
 * <p>
 * The relay exists because {@link UI#getCurrent()} is only bound while the UIDL
 * handler processes the request inside the session lock, and is cleared again
 * before the request ends. UIDL processing is synchronous on the request
 * thread, so a UI marked during handling is visible at the end of the request
 * on the same thread. The request binder clears the slot when the request
 * starts and consumes it when the request ends, so a pooled thread never leaks
 * a UI reference across requests.
 */
final class RequestUi {

    /**
     * Held weakly: {@code beforeEnter} also fires for a navigation started from
     * a background thread through {@code UI.access()}, where no request ever
     * ends to drain this slot — a strong reference would pin the UI (and
     * through it the session) to that pooled thread for the life of the server,
     * the same hazard {@code NavigationMetricsBinder#pendingUis} documents. A
     * stranded entry then costs a dead reference object, nothing more.
     */
    private static final ThreadLocal<WeakReference<UI>> CURRENT = new ThreadLocal<>();

    private RequestUi() {
    }

    /**
     * Records the UI the current request is being handled for. Last writer
     * wins; every marker within one request sees the same UI.
     */
    static void mark(UI ui) {
        if (ui != null) {
            CURRENT.set(new WeakReference<>(ui));
        }
    }

    /**
     * Returns and clears the UI for the current thread, or {@code null} if none
     * was marked.
     */
    static UI take() {
        WeakReference<UI> ref = CURRENT.get();
        CURRENT.remove();
        return ref == null ? null : ref.get();
    }

    /**
     * Clears any value left over from a previous request on this (pooled)
     * thread.
     */
    static void clear() {
        CURRENT.remove();
    }
}
