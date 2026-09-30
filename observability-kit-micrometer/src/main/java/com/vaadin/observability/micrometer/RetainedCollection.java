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
 * One collection a view instance held in one of its own fields when its UI was
 * measured by {@link UiStateSampler}.
 *
 * @param viewIdentity
 *            {@link System#identityHashCode(Object)} of the view instance, so
 *            two measurements of the same view can be compared without the
 *            sample keeping the view reachable after its navigation is over
 * @param viewClass
 *            fully qualified class name of the view instance
 * @param field
 *            the field, as {@code DeclaringClass.fieldName} with the declaring
 *            class fully qualified: where the code holding the collection is
 * @param route
 *            the view's route template, or {@code null} for a router layout or
 *            a view whose route cannot be resolved
 * @param elements
 *            elements in the collection, plus the elements of the collections
 *            nested directly inside it, see {@link RetainedCollections}
 */
record RetainedCollection(int viewIdentity, String viewClass, String field,
        String route, int elements) {

    /**
     * What identifies this collection across measurements: the same field of
     * the same view instance.
     */
    String key() {
        return viewIdentity + ":" + field;
    }
}
