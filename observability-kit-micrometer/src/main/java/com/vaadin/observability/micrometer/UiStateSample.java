/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

/**
 * What one UI's server-side state looked like at the moment it was measured by
 * {@link UiStateSampler}.
 *
 * @param nodes
 *            nodes in the UI's state tree: every node Flow retains to mirror
 *            the client, including the virtual children (dialogs, overlays,
 *            grid editors, and in Flow 25 the route target itself) that an
 *            {@code Element.getChildren()} walk cannot reach
 * @param components
 *            how many of those nodes carry a server-side {@code Component}
 *            instance, i.e. Java objects held for this browser tab
 * @param views
 *            route targets and router layouts retained in this UI's tree. A
 *            single navigation into a nested layout legitimately retains
 *            several, so this is a capacity figure, not a leak signal — that is
 *            {@link #staleViews()}
 * @param staleViews
 *            how many of those views are no longer part of the UI's active
 *            router-target chain, i.e. views that outlived their navigation.
 *            Normally {@code 0}
 * @param sampledAtNanos
 *            {@link System#nanoTime()} reading of the measurement. Monotonic on
 *            purpose: the ages and intervals derived from it must not be thrown
 *            off by a clock adjustment. A UI is measured while its own session
 *            lock is held, so a sample of an idle user's UI is as old as their
 *            last interaction
 */
record UiStateSample(int nodes, int components, int views, int staleViews,
        long sampledAtNanos) {
}
