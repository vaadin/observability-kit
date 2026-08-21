/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.observability.micrometer.trace.TracingExecutor;

class MetricsServiceInitListenerTracesTest {

    /**
     * Stand-in for a real {@code VaadinService} that reproduces the executor
     * resolution order of {@code VaadinService.init()}: the event carries no
     * executor while the init listeners run, and the service resolves one
     * through {@code createDefaultExecutor()} only afterwards.
     */
    private static class TestService extends VaadinServletService {

        private final Executor defaultExecutor;
        private final DeploymentConfiguration configuration = Mockito
                .mock(DeploymentConfiguration.class);
        private int createDefaultExecutorCalls;

        TestService(Executor defaultExecutor) {
            this.defaultExecutor = defaultExecutor;
            Mockito.when(configuration.isProductionMode()).thenReturn(true);
        }

        @Override
        public DeploymentConfiguration getDeploymentConfiguration() {
            return configuration;
        }

        @Override
        protected Executor createDefaultExecutor() {
            createDefaultExecutorCalls++;
            return defaultExecutor;
        }

        /**
         * Mimics what {@code VaadinService.init()} does after the listeners.
         */
        Executor resolveExecutor(ServiceInitEvent event) {
            return event.getExecutor().orElseGet(this::createDefaultExecutor);
        }
    }

    private static MetricsServiceInitListener listener(
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings) {
        return new MetricsServiceInitListener(new SimpleMeterRegistry(),
                observationRegistry, settings);
    }

    @AfterEach
    void tearDown() {
        ObservabilityKit.reset();
    }

    @Test
    void executorResolvedByFlowAfterListenersIsWrapped() {
        Executor original = Runnable::run;
        TestService service = new TestService(original);
        ServiceInitEvent event = new ServiceInitEvent(service);

        listener(ObservationRegistry.create(),
                ObservabilitySettings.builder().build()).serviceInit(event);

        Executor resolved = service.resolveExecutor(event);
        Assertions.assertInstanceOf(TracingExecutor.class, resolved,
                "the executor the service ends up with should be wrapped");
        Assertions.assertEquals(1, service.createDefaultExecutorCalls,
                "the service executor should be resolved exactly once");
    }

    @Test
    void executorSetByAnotherListenerIsWrapped() {
        TestService service = new TestService(Runnable::run);
        ServiceInitEvent event = new ServiceInitEvent(service);
        Executor fromOtherListener = Runnable::run;
        event.setExecutor(fromOtherListener);

        listener(ObservationRegistry.create(),
                ObservabilitySettings.builder().build()).serviceInit(event);

        Executor resolved = service.resolveExecutor(event);
        Assertions.assertInstanceOf(TracingExecutor.class, resolved);
        Assertions.assertEquals(0, service.createDefaultExecutorCalls,
                "an executor provided by another listener should be kept");
    }

    @Test
    void executorServiceKeepsItsLifecycleMethods() {
        // Vaadin only shuts its default executor down when it still is an
        // ExecutorService, so the wrapper has to remain one.
        ExecutorService original = Executors.newSingleThreadExecutor();
        try {
            TestService service = new TestService(original);
            ServiceInitEvent event = new ServiceInitEvent(service);

            listener(ObservationRegistry.create(),
                    ObservabilitySettings.builder().build()).serviceInit(event);

            Executor resolved = service.resolveExecutor(event);
            Assertions.assertInstanceOf(ExecutorService.class, resolved,
                    "wrapping must not strip the ExecutorService contract");
            List<Runnable> pending = ((ExecutorService) resolved).shutdownNow();
            Assertions.assertTrue(pending.isEmpty());
            Assertions.assertTrue(original.isShutdown(),
                    "shutdown should reach the wrapped executor");
        } finally {
            original.shutdownNow();
        }
    }

    @Test
    void executorIsNotWrappedWhenTracesDisabled() {
        Executor original = Runnable::run;
        TestService service = new TestService(original);
        ServiceInitEvent event = new ServiceInitEvent(service);

        listener(ObservationRegistry.create(),
                ObservabilitySettings.builder().traces(false).build())
                .serviceInit(event);

        Assertions.assertTrue(event.getExecutor().isEmpty(),
                "the service executor resolution should be left to Vaadin");
        Assertions.assertSame(original, service.resolveExecutor(event));
    }

    @Test
    void executorIsNotWrappedWhenObservationRegistryAbsent() {
        Executor original = Runnable::run;
        TestService service = new TestService(original);
        ServiceInitEvent event = new ServiceInitEvent(service);

        listener(null, ObservabilitySettings.builder().build())
                .serviceInit(event);

        Assertions.assertTrue(event.getExecutor().isEmpty(),
                "the service executor resolution should be left to Vaadin");
        Assertions.assertSame(original, service.resolveExecutor(event));
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
