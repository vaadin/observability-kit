/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads how many elements a view holds in the collections of its own fields.
 * <p>
 * This is the state the state-tree walk cannot see. A view that keeps every
 * result it ever loaded in a {@code List} field holds more heap on each refresh
 * while its component tree — and so every node, component and view count —
 * stays exactly the same size. Growth of such a field across measurements is
 * the signal {@link UiStateMetricsBinder} publishes.
 * <p>
 * <strong>What is read.</strong> The instance fields declared by the view's
 * class and its superclasses, up to the first Flow class: those are Flow's own
 * bookkeeping, not the application's. Of these, the fields whose declared type
 * is a {@link Collection}, a {@link Map} or an array. A collection nested
 * directly inside one of those counts too, so a {@code List<List<T>>} that
 * gains a whole result set per refresh grows by that result set rather than by
 * one; only the first {@link #NESTED_LIMIT} entries are looked into, which
 * keeps a sample bounded without letting a growing collection ever read as a
 * shrinking one.
 * <p>
 * <strong>What is not read.</strong> Only {@code java.util} implementations are
 * asked for their size. Any other collection may do work to answer — a lazy JPA
 * association loads itself from the database on {@code size()} — and a
 * measurement must never be what issues a query or changes what the view holds.
 * Nothing reachable from an element is followed either: this counts what a
 * field holds, not how much heap that is.
 * <p>
 * <strong>Threading:</strong> called during the state-tree walk, so under the
 * view's session lock. A collection that a background thread changes without
 * that lock can still fail to iterate; such a field is left out of the sample
 * rather than failing it.
 */
final class RetainedCollections {

    private static final Logger LOGGER = LoggerFactory
            .getLogger(RetainedCollections.class);

    /** Entries of one collection looked into for nested collections. */
    static final int NESTED_LIMIT = 1000;

    /** Package prefix of the framework classes whose fields are not read. */
    private static final String FLOW_PACKAGE = "com.vaadin.flow.";

    /** Package prefix of the collections trusted to answer without work. */
    private static final String JAVA_UTIL_PACKAGE = "java.util.";

    /**
     * The collection-typed fields of a class, found once per class: a tree
     * holds many instances of few view classes.
     */
    private static final ClassValue<List<Field>> FIELDS = new ClassValue<>() {
        @Override
        protected List<Field> computeValue(Class<?> type) {
            return collectionFields(type);
        }
    };

    private RetainedCollections() {
    }

    /**
     * Measures the collections one view holds.
     *
     * @param view
     *            the view instance, not {@code null}
     * @param route
     *            the view's route template, or {@code null}
     * @param into
     *            where the measurements are added
     */
    static void measure(Object view, String route,
            List<RetainedCollection> into) {
        List<Field> fields = FIELDS.get(view.getClass());
        if (fields.isEmpty()) {
            return;
        }
        int identity = System.identityHashCode(view);
        String viewClass = view.getClass().getName();
        for (Field field : fields) {
            try {
                int elements = elements(field.get(view));
                if (elements >= 0) {
                    into.add(
                            new RetainedCollection(identity, viewClass,
                                    field.getDeclaringClass().getName() + "."
                                            + field.getName(),
                                    route, elements));
                }
            } catch (IllegalAccessException | RuntimeException e) {
                LOGGER.debug(
                        "Could not read the size of {}.{}, it is left "
                                + "out of the retained state metrics",
                        field.getDeclaringClass().getName(), field.getName(),
                        e);
            }
        }
    }

    /**
     * Elements in a field value, plus the elements of the collections nested
     * directly inside it, or {@code -1} when the value is not something this
     * class reads.
     */
    private static int elements(Object value) {
        if (value == null) {
            return -1;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            if (value instanceof Object[] array) {
                return length + nested(Arrays.asList(array));
            }
            return length;
        }
        if (!isTrusted(value)) {
            return -1;
        }
        if (value instanceof Collection<?> collection) {
            return collection.size() + nested(collection);
        }
        if (value instanceof Map<?, ?> map) {
            return map.size() + nested(map.values());
        }
        return -1;
    }

    /**
     * Sizes of the trusted collections among the first {@link #NESTED_LIMIT}
     * entries.
     */
    private static int nested(Collection<?> entries) {
        int nested = 0;
        int seen = 0;
        for (Object entry : entries) {
            if (seen++ >= NESTED_LIMIT) {
                break;
            }
            if (entry != null && isTrusted(entry)) {
                if (entry instanceof Collection<?> collection) {
                    nested += collection.size();
                } else if (entry instanceof Map<?, ?> map) {
                    nested += map.size();
                }
            } else if (entry instanceof Object[] array) {
                nested += array.length;
            }
        }
        return nested;
    }

    /**
     * Whether a collection can be asked for its size without doing work: only
     * the JDK's own implementations are known to answer from memory.
     */
    private static boolean isTrusted(Object value) {
        return value.getClass().getName().startsWith(JAVA_UTIL_PACKAGE);
    }

    private static List<Field> collectionFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class
                && !current.getName().startsWith(
                        FLOW_PACKAGE); current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (isCollectionField(field) && field.trySetAccessible()) {
                    fields.add(field);
                }
            }
        }
        return List.copyOf(fields);
    }

    private static boolean isCollectionField(Field field) {
        if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
            return false;
        }
        Class<?> type = field.getType();
        return type.isArray() || Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type);
    }
}
