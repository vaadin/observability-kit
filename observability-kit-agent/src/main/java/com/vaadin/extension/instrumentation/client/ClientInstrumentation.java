/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.extension.instrumentation.client;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import com.vaadin.extension.conf.ConfigurationDefaults;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * This instrumentation is applied to the ObservabilityHandler constructor.
 * Because the agent and starter use different class loaders, the code
 * to construct SpanData instances and export them must reside entirely
 * within either the agent or the starter.
 * To achieve this, this injects a consumer into the ObservabilityHandler
 * class. The ObservabilityHandler converts the incoming JSON string into
 * a Map of objects that are only contained within the java.util and
 * java .lang packages. It then sends this to the consumer. This means that
 * there are no class loader issues.
 */
public class ClientInstrumentation implements TypeInstrumentation {
    /**
     * Returns an element matcher for the ObservabilityHandler class.
     *
     * @return an element matcher
     */
    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("com.vaadin.observability.ObservabilityHandler");
    }

    /**
     * Returns an element matcher for the ObservabilityHandler class.
     *
     * @return an element matcher
     */
    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.observability.ObservabilityHandler");
    }

    /**
     * Sets callbacks on the ConstructorAdvice class and applies this class
     * to the constructor of an ObservabilityHandler instance.
     *
     * @param transformer the TypeTransformer
     */
    @SuppressWarnings("unchecked")
    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyTransformer((builder, typeDescription, classLoader,
                module, protectionDomain) -> {
            try {
                Class<?> helperClazz = classLoader.loadClass(
                        ConstructorAdvice.class.getName());

                Field functionField = helperClazz.getField("configHolder");
                AtomicReference<Function<String,String>> functionHolder =
                        (AtomicReference<Function<String,String>>) functionField.get(null);
                functionHolder.set((key) ->
                        ConfigurationDefaults.configProperties.getString(key));

                Field consumerField = helperClazz.getField("exportHolder");
                AtomicReference<BiConsumer<String,
                        Map<String, Object>>> consumerHolder =
                        (AtomicReference<BiConsumer<String,
                                Map<String, Object>>>) consumerField.get(null);
                consumerHolder.set(new ObjectMapExporter());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return builder;
        });
        // Apply the advice to both the constructor and the readObject
        // method, which is called during deserialization.
        transformer.applyAdviceToMethod(isConstructor().or(named("readObject")),
                this.getClass().getName() + "$ConstructorAdvice");
    }

    /**
     * Class representing the code injected into the ObservabilityHandler
     * constructor. Two callbacks are set on the new instance - a config
     * function and an exporter consumer. These allow the handler to retrieve
     * configuration properties and to export traces, respectively.
     */
    public static class ConstructorAdvice {
        public static AtomicReference<Function<String,String>> configHolder =
                new AtomicReference<>();
        public static AtomicReference<BiConsumer<String,
                Map<String, Object>>> exportHolder = new AtomicReference<>();

        @Advice.OnMethodExit()
        public static void onExit(
                @Advice.FieldValue(value = "config", readOnly = false)
                Function<String, String> config,
                @Advice.FieldValue(value = "exporter", readOnly = false)
                BiConsumer<String, Map<String, Object>> exporter) {
            config = ConstructorAdvice.configHolder.get();
            exporter = ConstructorAdvice.exportHolder.get();
        }
    }
}
