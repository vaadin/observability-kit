/**
 * Copyright (C) 2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.observability.micrometer.MetricsServiceInitListener;
import com.vaadin.observability.micrometer.ObservabilitySettings;

import static org.assertj.core.api.Assertions.assertThatCode;

class SpringMetricsServiceInitListenerTest {

    /**
     * Neither the starter nor vaadin-spring bring spring-web at runtime, so the
     * listener must not touch Spring's HTTP observation classes when they are
     * missing. Linking against them there would throw
     * {@link NoClassDefFoundError}, which no best-effort catch stops.
     */
    @Test
    void enrichmentIsSkippedWithoutSpringWeb() throws Exception {
        ClassLoader withoutSpringWeb = new WithoutSpringWebClassLoader(
                getClass().getClassLoader());
        Class<?> listenerClass = withoutSpringWeb
                .loadClass(SpringMetricsServiceInitListener.class.getName());
        Constructor<?> constructor = listenerClass.getConstructor(
                MeterRegistry.class, ObservationRegistry.class,
                ObservabilitySettings.class);
        Object listener = constructor.newInstance(new SimpleMeterRegistry(),
                ObservationRegistry.NOOP,
                ObservabilitySettings.builder().build());
        VaadinRequest request = Mockito.mock(VaadinRequest.class);

        assertThatCode(() -> {
            invoke(listener, "enrichHttpObservation", String.class, request,
                    "uidl");
            invoke(listener, "enrichHttpObservationRoute", String.class,
                    request, "orders/:id");
            invoke(listener, "markHttpObservationError", Throwable.class,
                    request, new IllegalStateException("boom"));
        }).doesNotThrowAnyException();
    }

    private static void invoke(Object listener, String name,
            Class<?> argumentType, VaadinRequest request, Object argument)
            throws Exception {
        Method method = MetricsServiceInitListener.class.getDeclaredMethod(name,
                VaadinRequest.class, argumentType);
        method.setAccessible(true);
        method.invoke(listener, request, argument);
    }

    /**
     * Loads this module's classes itself and hides spring-web, delegating
     * everything else to the test class loader.
     */
    private static final class WithoutSpringWebClassLoader extends ClassLoader {

        private static final String OWN_PACKAGE = SpringMetricsServiceInitListener.class
                .getPackageName() + ".";

        WithoutSpringWebClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith("org.springframework.web.")
                        || name.startsWith("org.springframework.http.")) {
                    throw new ClassNotFoundException(name);
                }
                if (!name.startsWith(OWN_PACKAGE)) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = define(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> define(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public java.net.URL getResource(String name) {
            if (name.startsWith("org/springframework/web/")
                    || name.startsWith("org/springframework/http/")) {
                return null;
            }
            return super.getResource(name);
        }
    }
}
