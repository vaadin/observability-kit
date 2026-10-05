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
 * The bounded set of exception-type tag values, shared by every meter that tags
 * a failure with its type: the {@code exception} tag of
 * {@link MeterNames#ERRORS} and the {@code error} tag of the request and RPC
 * timers.
 * <p>
 * One instance per service, so all of them draw from one budget of
 * {@link ObservabilitySettings#getRouteCardinalityLimit()} types: a budget per
 * meter would let the same exception type be itself on one meter and
 * {@link MeterNames#EXCEPTION_OTHER} on another.
 */
final class ExceptionTags {

    private final BoundedTagValues types;

    ExceptionTags(ObservabilitySettings settings) {
        this.types = new BoundedTagValues(settings.getRouteCardinalityLimit(),
                MeterNames.EXCEPTION_OTHER);
    }

    /**
     * The tag value for a failure's type: its simple class name while the
     * budget lasts, {@link MeterNames#EXCEPTION_OTHER} after.
     *
     * @param error
     *            the failure, not {@code null}
     * @return the tag value, never {@code null}
     */
    String tag(Throwable error) {
        Class<?> type = error.getClass();
        String name = type.getSimpleName();
        // A lambda or an anonymous Throwable subclass has an empty simple
        // name; the binary name at least says where it was declared.
        return types.admit(name.isEmpty() ? type.getName() : name);
    }
}
