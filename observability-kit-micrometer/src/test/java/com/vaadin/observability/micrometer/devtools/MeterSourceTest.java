/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.devtools;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.step.StepRegistryConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MeterSourceTest {

    /**
     * A step registry that never publishes: what Datadog, Elastic and the other
     * push registries are as far as reading a meter goes.
     */
    static StepMeterRegistry stepRegistry() {
        StepRegistryConfig config = new StepRegistryConfig() {
            @Override
            public String prefix() {
                return "test";
            }

            @Override
            public String get(String key) {
                return null;
            }
        };
        return new StepMeterRegistry(config, Clock.SYSTEM) {
            @Override
            protected void publish() {
            }

            @Override
            protected TimeUnit getBaseTimeUnit() {
                return TimeUnit.MILLISECONDS;
            }
        };
    }

    @Test
    void plainRegistry_isReadAsItIs() {
        SimpleMeterRegistry simple = new SimpleMeterRegistry();

        Assertions.assertEquals(new MeterSource(simple, false),
                MeterSource.of(simple));
    }

    @Test
    void composite_isReadThroughItsCumulativeChild() {
        SimpleMeterRegistry simple = new SimpleMeterRegistry();
        CompositeMeterRegistry composite = new CompositeMeterRegistry();
        composite.add(stepRegistry());
        composite.add(simple);

        // Whichever order the composite holds them in: reading the composite
        // itself would answer from either.
        Assertions.assertEquals(new MeterSource(simple, false),
                MeterSource.of(composite));
    }

    @Test
    void nestedComposite_isLookedThrough() {
        SimpleMeterRegistry simple = new SimpleMeterRegistry();
        CompositeMeterRegistry inner = new CompositeMeterRegistry();
        inner.add(simple);
        CompositeMeterRegistry outer = new CompositeMeterRegistry();
        outer.add(stepRegistry());
        outer.add(inner);

        Assertions.assertSame(simple, MeterSource.of(outer).registry());
    }

    @Test
    void onlyStepRegistries_areReadPerInterval() {
        StepMeterRegistry step = stepRegistry();
        CompositeMeterRegistry composite = new CompositeMeterRegistry();
        composite.add(step);

        Assertions.assertEquals(new MeterSource(step, true),
                MeterSource.of(composite));
        Assertions.assertTrue(MeterSource.of(step).perInterval());
    }

    @Test
    void emptyComposite_isReadAsItIs() {
        CompositeMeterRegistry composite = new CompositeMeterRegistry();

        Assertions.assertEquals(new MeterSource(composite, false),
                MeterSource.of(composite));
    }
}
