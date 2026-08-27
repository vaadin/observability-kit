/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;

/**
 * Verifies that every tag value of {@link MeterNames#ERRORS} is capped. All
 * three derive from application classes and multiply with each other, so an
 * unbounded one is enough to flood the meter on its own.
 */
class ErrorCounterTest {

    @Tag("first-button")
    private static final class FirstButton extends Component {
    }

    @Tag("second-button")
    private static final class SecondButton extends Component {
    }

    private static final class FirstFailure extends RuntimeException {
    }

    private static final class SecondFailure extends RuntimeException {
    }

    private SimpleMeterRegistry registry;
    private ErrorCounter errors;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // Room for exactly one value per tag, so the second one overflows.
        errors = new ErrorCounter(registry, ObservabilitySettings.builder()
                .routeCardinalityLimit(1).build());
    }

    private Counter counter(String exception, String component) {
        return registry.find(MeterNames.ERRORS)
                .tag(MeterNames.TAG_EXCEPTION, exception)
                .tag(MeterNames.TAG_COMPONENT, component).counter();
    }

    @Test
    void componentsBeyondTheLimitAreBucketed() {
        errors.increment(new FirstFailure(), new FirstButton());
        errors.increment(new FirstFailure(), new SecondButton());

        Assertions.assertNotNull(counter("FirstFailure", "FirstButton"),
                "the first component must be tagged by its own name");
        Counter overflow = counter("FirstFailure", MeterNames.COMPONENT_OTHER);
        Assertions.assertNotNull(overflow,
                "a component beyond the limit must be bucketed as _other");
        Assertions.assertEquals(1.0, overflow.count(), 0.0,
                "bucketing must not lose the count");
    }

    @Test
    void exceptionTypesBeyondTheLimitAreBucketed() {
        errors.increment(new FirstFailure(), new FirstButton());
        errors.increment(new SecondFailure(), new FirstButton());

        Assertions.assertNotNull(counter("FirstFailure", "FirstButton"),
                "the first exception type must be tagged by its own name");
        Counter overflow = counter(MeterNames.EXCEPTION_OTHER, "FirstButton");
        Assertions.assertNotNull(overflow,
                "an exception type beyond the limit must be bucketed as _other");
        Assertions.assertEquals(1.0, overflow.count(), 0.0,
                "bucketing must not lose the count");
    }

    @Test
    void aKnownExceptionTypeKeepsItsOwnTagAfterOverflow() {
        errors.increment(new FirstFailure(), new FirstButton());
        errors.increment(new SecondFailure(), new FirstButton());
        errors.increment(new FirstFailure(), new FirstButton());

        Assertions.assertEquals(2.0,
                counter("FirstFailure", "FirstButton").count(), 0.0,
                "overflow must not retag the types admitted before it");
    }
}
