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

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

public class HillaClientInstrumentation implements TypeInstrumentation {
    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("com.vaadin.observability.ObservabilityEndpoint");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.observability.ObservabilityEndpoint");
    }

    @SuppressWarnings("unchecked")
    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyTransformer((builder, typeDescription, classLoader,
                module, protectionDomain) -> {
            try {
                Class<?> helperClazz = classLoader.loadClass(
                        ConstructorAdvice.class.getName());

                Field exportField = helperClazz.getField("exportHolder");
                AtomicReference<BiConsumer<String,
                        Map<String, Object>>> exportHolder =
                        (AtomicReference<BiConsumer<String,
                                Map<String, Object>>>) exportField.get(null);
                exportHolder.set(new ObjectMapExporter());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return builder;
        });
        transformer.applyAdviceToMethod(isConstructor(),
                this.getClass().getName() + "$ConstructorAdvice");
    }

    public static class ConstructorAdvice {
        public static AtomicReference<BiConsumer<String,
                Map<String, Object>>> exportHolder = new AtomicReference<>();

        @Advice.OnMethodExit()
        public static void onExit(
                @Advice.FieldValue(value = "exporter", readOnly = false)
                BiConsumer<String, Map<String, Object>> exporter) {
            exporter = ClientInstrumentation.ConstructorAdvice.exportHolder.get();
        }
    }
}
