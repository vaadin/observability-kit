/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

/**
 * Trivial token bucket: refills {@code ratePerWindow} tokens every
 * {@code windowMs} milliseconds. Not thread safe in the strict sense, but
 * synchronized on the instance, which is sufficient because there is one
 * limiter per UI.
 */
final class ClientRateLimiter {

    private static final long WINDOW_MS = 10_000L;

    private final int ratePerWindow;
    private long windowStart;
    private int consumed;

    ClientRateLimiter(int ratePerWindow) {
        this.ratePerWindow = ratePerWindow;
        this.windowStart = System.currentTimeMillis();
    }

    /**
     * Attempts to consume {@code n} tokens. Returns the number actually granted
     * (between 0 and {@code n}); the caller must drop any samples beyond that.
     */
    synchronized int tryAcquire(int n) {
        if (ratePerWindow <= 0) {
            return n;
        }
        long now = System.currentTimeMillis();
        if (now - windowStart >= WINDOW_MS) {
            windowStart = now;
            consumed = 0;
        }
        int remaining = Math.max(0, ratePerWindow - consumed);
        int granted = Math.min(n, remaining);
        consumed += granted;
        return granted;
    }
}
