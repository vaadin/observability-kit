/**
 * Copyright (C) 2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.spring.SpringVaadinServletService;
import com.vaadin.flow.spring.annotation.VaadinTaskExecutor;
import com.vaadin.observability.micrometer.ObservabilitySettings;
import com.vaadin.observability.micrometer.trace.TracingExecutor;

/**
 * Verifies that the executor Vaadin would pick in a Spring application ends up
 * wrapped for tracing, and that Vaadin's own {@code TaskExecutor} bean
 * selection is the thing that picks it.
 *
 * @see <a href=
 *      "https://github.com/vaadin/observability-kit/issues/353">#353</a>
 */
class SpringServiceExecutorWrappingTest {

    /** Inline executor that counts the tasks it is given. */
    static final class CountingExecutor implements TaskExecutor {
        final AtomicInteger executed = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            executed.incrementAndGet();
            command.run();
        }
    }

    @Configuration
    static class SingleExecutorConfig {
        @Bean
        CountingExecutor applicationTaskExecutor() {
            return new CountingExecutor();
        }
    }

    @Configuration
    static class VaadinSpecificExecutorConfig {
        @Bean
        CountingExecutor applicationTaskExecutor() {
            return new CountingExecutor();
        }

        @Bean(VaadinTaskExecutor.NAME)
        CountingExecutor vaadinTaskExecutor() {
            return new CountingExecutor();
        }
    }

    private static Executor wrappedExecutor(
            AnnotationConfigApplicationContext context) {
        DeploymentConfiguration configuration = Mockito
                .mock(DeploymentConfiguration.class);
        Mockito.when(configuration.isProductionMode()).thenReturn(true);
        SpringVaadinServletService service = new SpringVaadinServletService(
                null, configuration, context);
        ServiceInitEvent event = new ServiceInitEvent(service);

        new SpringMetricsServiceInitListener(new SimpleMeterRegistry(),
                ObservationRegistry.create(),
                ObservabilitySettings.builder().build()).serviceInit(event);

        Optional<Executor> executor = event.getExecutor();
        Assertions.assertTrue(executor.isPresent(),
                "the listener should hand Vaadin an executor to use");
        Assertions.assertInstanceOf(TracingExecutor.class, executor.get());
        return executor.get();
    }

    @Configuration
    static class AmbiguousExecutorsConfig {
        @Bean
        CountingExecutor firstExecutor() {
            return new CountingExecutor();
        }

        @Bean
        CountingExecutor secondExecutor() {
            return new CountingExecutor();
        }
    }

    @Test
    void springTaskExecutorBeanIsWrapped() {
        try (var context = new AnnotationConfigApplicationContext(
                SingleExecutorConfig.class)) {
            Executor wrapped = wrappedExecutor(context);

            wrapped.execute(() -> {
            });

            Assertions.assertEquals(1,
                    context.getBean(CountingExecutor.class).executed.get(),
                    "tasks should reach the Spring TaskExecutor bean");
        }
    }

    @Test
    void vaadinSpecificExecutorSelectionIsPreserved() {
        try (var context = new AnnotationConfigApplicationContext(
                VaadinSpecificExecutorConfig.class)) {
            Executor wrapped = wrappedExecutor(context);

            wrapped.execute(() -> {
            });

            CountingExecutor vaadinExecutor = context
                    .getBean(VaadinTaskExecutor.NAME, CountingExecutor.class);
            CountingExecutor applicationExecutor = context
                    .getBean("applicationTaskExecutor", CountingExecutor.class);
            Assertions.assertEquals(1, vaadinExecutor.executed.get(),
                    "the Vaadin-specific executor bean should be used");
            Assertions.assertEquals(0, applicationExecutor.executed.get(),
                    "Vaadin's bean selection must not be bypassed");
        }
    }

    @Test
    void ambiguousExecutorCandidatesStillFailAsVaadinIntends() {
        try (var context = new AnnotationConfigApplicationContext(
                AmbiguousExecutorsConfig.class)) {
            DeploymentConfiguration configuration = Mockito
                    .mock(DeploymentConfiguration.class);
            Mockito.when(configuration.isProductionMode()).thenReturn(true);
            SpringVaadinServletService service = new SpringVaadinServletService(
                    null, configuration, context);

            new SpringMetricsServiceInitListener(new SimpleMeterRegistry(),
                    ObservationRegistry.create(),
                    ObservabilitySettings.builder().build())
                    .serviceInit(new ServiceInitEvent(service));

            // Resolving the executor detects the ambiguity, exactly as it does
            // without Observability Kit on the classpath.
            Assertions.assertThrows(IllegalStateException.class,
                    service::getExecutor);
        }
    }
}
