/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.Optional;
import java.util.concurrent.Executor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.observability.micrometer.trace.TracingExecutor;

class MetricsServiceInitListenerTracesTest {

    @AfterEach
    void tearDown() {
        ObservabilityKit.reset();
    }

    @Test
    void executorIsWrappedWhenTracesEnabled() {
        ObservationRegistry obs = ObservationRegistry.create();
        MetricsServiceInitListener listener = new MetricsServiceInitListener(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().build());

        VaadinService service = Mockito.mock(VaadinService.class,
                Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(true);
        ServiceInitEvent event = new ServiceInitEvent(service);
        Executor original = Runnable::run;
        event.setExecutor(original);

        listener.serviceInit(event);

        Optional<Executor> after = event.getExecutor();
        Assertions.assertTrue(after.isPresent());
        Assertions.assertInstanceOf(TracingExecutor.class, after.get(),
                "executor should be wrapped in TracingExecutor");
    }

    @Test
    void executorIsNotWrappedWhenTracesDisabled() {
        ObservationRegistry obs = ObservationRegistry.create();
        MetricsServiceInitListener listener = new MetricsServiceInitListener(
                new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().traces(false).build());

        VaadinService service = Mockito.mock(VaadinService.class,
                Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(true);
        ServiceInitEvent event = new ServiceInitEvent(service);
        Executor original = Runnable::run;
        event.setExecutor(original);

        listener.serviceInit(event);

        Assertions.assertSame(original, event.getExecutor().orElse(null),
                "executor should remain unwrapped");
    }

    @Test
    void executorIsNotWrappedWhenObservationRegistryAbsent() {
        MetricsServiceInitListener listener = new MetricsServiceInitListener(
                new SimpleMeterRegistry(), null,
                ObservabilitySettings.builder().build());

        VaadinService service = Mockito.mock(VaadinService.class,
                Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(true);
        ServiceInitEvent event = new ServiceInitEvent(service);
        Executor original = Runnable::run;
        event.setExecutor(original);

        listener.serviceInit(event);

        Assertions.assertSame(original, event.getExecutor().orElse(null));
    }

    @Test
    void noOpInstallHookCanBeOverriddenBySubclass() {
        final boolean[] called = { false };
        ObservationRegistry obs = ObservationRegistry.create();
        new MetricsServiceInitListener(new SimpleMeterRegistry(), obs,
                ObservabilitySettings.builder().build()) {
            @Override
            protected void installDefaultObservationHandlers(
                    ObservationRegistry r, MeterRegistry mr) {
                called[0] = true;
            }
        };
        Assertions.assertTrue(called[0],
                "subclass override should be dispatched from base ctor");
    }
}
