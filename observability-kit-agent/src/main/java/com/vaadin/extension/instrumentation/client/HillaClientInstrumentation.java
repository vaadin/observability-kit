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

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class HillaClientInstrumentation implements TypeInstrumentation {
    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.observability.ObservabilityEndpoint");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.observability.ObservabilityEndpoint");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyTransformer((builder, typeDescription, classLoader,
                module, protectionDomain) -> {
            try {
                var cls = classLoader
                        .loadClass(ExportMethodAdvice.class.getName());
                var field = cls.getDeclaredField("holder");
                var ref = (AtomicReference<BiConsumer<String, Map<String, Object>>>) field
                        .get(null);
                ref.set(new ObjectMapExporter());
                return builder;
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });

        transformer.applyAdviceToMethod(named("export"),
                this.getClass().getName() + "$ExportMethodAdvice");
    }

    public static class ExportMethodAdvice {
        public static AtomicReference<BiConsumer<String, Map<String, Object>>> holder = new AtomicReference<>();

        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.FieldValue(value = "exporter", readOnly = false) BiConsumer<String, Map<String, Object>> exporter) {
            exporter = holder.get();
        }
    }
}
