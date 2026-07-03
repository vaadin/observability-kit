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
 * Fake business service with a planted bug: order 1042 has no warehouse
 * allocation, so shipping it fails with a {@link NullPointerException} deep in
 * "business" code. This is the bug the insights endpoint should let a developer
 * (or AI agent) find from the user report "I clicked Ship and got an error".
 */
public class OrderShippingService {

    /** Warehouse allocation per order id; 1042 is intentionally missing. */
    private static final Map<Long, Allocation> ALLOCATIONS = Map.of(1040L,
            new Allocation("A-11"), 1041L, new Allocation("A-12"), 1043L,
            new Allocation("B-07"));

    public void ship(long orderId) {
        Allocation allocation = ALLOCATIONS.get(orderId);
        // BUG: no null check; order 1042 has no allocation.
        allocation.reserve();
    }

    record Allocation(String bay) {
        void reserve() {
            // pretend to talk to the warehouse system
        }
    }
}
