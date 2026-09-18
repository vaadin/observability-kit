/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.LinkedHashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;

/**
 * Which of a UI's fields the user has worked, so that a replay can tell the
 * values they set apart from the ones the view came with.
 * <p>
 * A replay starts from a freshly opened view, which means every field the user
 * never touched is already at the value the reader would find there. Telling
 * them to set it is noise at best — four lines of "set this to what it already
 * says" around the one line that matters — so only what the user changed is
 * worth an instruction.
 * <p>
 * What is remembered is identity, not values: the node id of each field an
 * invocation targeted. The values themselves are read from the components when
 * an interaction is captured, so nothing here can go stale, and a user who
 * changes their mind is one field with one value rather than a history.
 * <p>
 * Held on the UI itself, so it goes away with the tab rather than having to be
 * reaped, and touched only while the session lock is held — the contract of RPC
 * handling — so it needs no synchronization of its own.
 */
final class TouchedFields {

    /**
     * Fields remembered per UI. Beyond this the oldest is forgotten, which
     * costs a replay line for a field the user set and then left alone for
     * fifty other fields — while the alternative is a set that grows with
     * however long a tab stays open.
     */
    static final int MAX_TRACKED = 50;

    private final Set<Integer> nodeIds = new LinkedHashSet<>();

    private TouchedFields() {
    }

    /**
     * The record for a UI, created and attached on first use.
     *
     * @param ui
     *            the UI whose fields are tracked, may be {@code null}
     * @return the record, or {@code null} when there is no UI to hold one
     */
    static @Nullable TouchedFields of(@Nullable UI ui) {
        if (ui == null) {
            return null;
        }
        TouchedFields touched = ComponentUtil.getData(ui, TouchedFields.class);
        if (touched == null) {
            touched = new TouchedFields();
            ComponentUtil.setData(ui, TouchedFields.class, touched);
        }
        return touched;
    }

    /** Remembers that the user worked this component. */
    void add(Component component) {
        Integer nodeId = nodeIdOf(component);
        if (nodeId == null) {
            return;
        }
        // Re-inserting must move it to the end, so that a field worked
        // throughout a long session is not the one evicted.
        nodeIds.remove(nodeId);
        if (nodeIds.size() >= MAX_TRACKED) {
            nodeIds.remove(nodeIds.iterator().next());
        }
        nodeIds.add(nodeId);
    }

    /** Whether the user worked this component. */
    boolean contains(Component component) {
        Integer nodeId = nodeIdOf(component);
        return nodeId != null && nodeIds.contains(nodeId);
    }

    /**
     * The state node behind a component, which identifies it for as long as it
     * exists. Ids are never reused within a state tree, so an entry left over
     * from a view the user has navigated away from can match nothing.
     * <p>
     * A node that is not attached to a tree has no id of its own — every one of
     * them reports {@code -1} — so a detached component is nobody, rather than
     * everybody.
     */
    private static @Nullable Integer nodeIdOf(Component component) {
        try {
            int nodeId = component.getElement().getNode().getId();
            return nodeId < 0 ? null : nodeId;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
