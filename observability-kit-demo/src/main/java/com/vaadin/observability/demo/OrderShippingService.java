/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.demo;

import java.util.Map;

/**
 * Fake business service with two planted problems the insights endpoint should
 * let a developer (or AI agent) find from vague user reports:
 * <ul>
 * <li>order 1042 has no warehouse allocation, so shipping it fails with a
 * {@link NullPointerException} deep in "business" code ("I clicked Ship and
 * got an error");</li>
 * <li>order 1041 is allocated in the LEGACY warehouse, whose synchronous
 * client blocks for seconds ("I clicked Ship and it hung forever").</li>
 * </ul>
 */
public class OrderShippingService {

    /** Warehouse allocation per order id; 1042 is intentionally missing. */
    private static final Map<Long, Allocation> ALLOCATIONS = Map.of(1040L,
            new Allocation("A-11"), 1041L, new Allocation("LEGACY-3"), 1043L,
            new Allocation("B-07"));

    public void ship(long orderId) {
        Allocation allocation = ALLOCATIONS.get(orderId);
        // BUG: no null check; order 1042 has no allocation.
        allocation.reserve();
    }

    record Allocation(String bay) {
        void reserve() {
            if (bay.startsWith("LEGACY")) {
                // BUG: synchronous call to the legacy warehouse system, which
                // takes seconds to answer, blocking the UI the whole time.
                legacyWarehouseCall();
            }
        }

        private static void legacyWarehouseCall() {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
