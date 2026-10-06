/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import java.util.List;
import java.util.stream.Stream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.observability.micrometer.MeterNames;
import com.vaadin.observability.micrometer.ObservabilitySettings;

class MetricsCollectorElementTest {

    private SimpleMeterRegistry registry;
    private ObservabilitySettings settings;
    private ClientMetricsBinder binder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        settings = ObservabilitySettings.builder().clientRatePerSession(3)
                .build();
        binder = new ClientMetricsBinder(registry, settings);
    }

    @AfterEach
    void tearDown() {
        VaadinSession.setCurrent(null);
    }

    /** A session that reports its lock as held, as Flow's would here. */
    private static VaadinSession lockedSession() {
        return new VaadinSession(Mockito.mock(VaadinService.class)) {
            @Override
            public boolean hasLock() {
                return true;
            }
        };
    }

    private MetricsCollectorElement tab() {
        return new MetricsCollectorElement(binder, settings, false);
    }

    private double throttled() {
        return registry.counter(MeterNames.CLIENT_THROTTLED).count();
    }

    private static List<ClientSample> samples(int count) {
        return Stream.generate(ClientSample::new).limit(count).toList();
    }

    @Test
    void tabsOfOneSessionShareTheBudget() {
        VaadinSession.setCurrent(lockedSession());
        MetricsCollectorElement first = tab();
        MetricsCollectorElement second = tab();

        first.recordSamples(samples(2));
        second.recordSamples(samples(2));

        Assertions.assertEquals(1.0, throttled(),
                "a second tab must not get a budget of its own");
    }

    @Test
    void sessionsHaveABudgetEach() {
        MetricsCollectorElement first = tab();
        MetricsCollectorElement second = tab();

        VaadinSession.setCurrent(lockedSession());
        first.recordSamples(samples(3));
        VaadinSession.setCurrent(lockedSession());
        second.recordSamples(samples(3));

        Assertions.assertEquals(0.0, throttled());
    }

    @Test
    void withoutASessionTheElementStillLimits() {
        MetricsCollectorElement element = tab();

        element.recordSamples(samples(5));

        Assertions.assertEquals(2.0, throttled());
    }
}
