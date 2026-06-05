/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ClientRateLimiter}.
 */
class ClientRateLimiterTest {

    @Test
    void grantsUpToRateWithinWindow() {
        ClientRateLimiter limiter = new ClientRateLimiter(5);
        assertEquals(5, limiter.tryAcquire(5));
    }

    @Test
    void partialGrantWhenBudgetExhausted() {
        ClientRateLimiter limiter = new ClientRateLimiter(3);
        // Consume all 3 tokens
        assertEquals(3, limiter.tryAcquire(3));
        // No more budget in this window
        assertEquals(0, limiter.tryAcquire(2));
    }

    @Test
    void partialGrantStopsAtRemaining() {
        ClientRateLimiter limiter = new ClientRateLimiter(4);
        // Consume 2 tokens first
        assertEquals(2, limiter.tryAcquire(2));
        // Only 2 remaining; requesting 5 should grant 2
        assertEquals(2, limiter.tryAcquire(5));
    }

    @Test
    void zeroRateGrantsUnlimited() {
        // ratePerWindow <= 0 means unlimited
        ClientRateLimiter limiter = new ClientRateLimiter(0);
        assertEquals(100, limiter.tryAcquire(100));
        assertEquals(1000, limiter.tryAcquire(1000));
    }

    @Test
    void negativeRateGrantsUnlimited() {
        ClientRateLimiter limiter = new ClientRateLimiter(-1);
        assertEquals(50, limiter.tryAcquire(50));
    }

    @Test
    void requestMoreThanRateGrantsRate() {
        ClientRateLimiter limiter = new ClientRateLimiter(10);
        // Request more than the window budget allows
        assertEquals(10, limiter.tryAcquire(20));
    }

    @Test
    void singleTokenGranted() {
        ClientRateLimiter limiter = new ClientRateLimiter(1);
        assertEquals(1, limiter.tryAcquire(1));
        // Second call in same window: budget exhausted
        assertEquals(0, limiter.tryAcquire(1));
    }
}
