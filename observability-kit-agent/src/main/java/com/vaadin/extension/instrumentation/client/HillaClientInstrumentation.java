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
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.StubMethod;
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

                return builder.invokable(isTypeInitializer())
                        .intercept(Advice.to(ExportMethodAdvice.class)
                                .wrap(StubMethod.INSTANCE));
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static class ExportMethodAdvice {
        public static AtomicReference<BiConsumer<String, Map<String, Object>>> holder = new AtomicReference<>();

        @Advice.OnMethodExit()
        public static void onExit(
                @Advice.FieldValue(value = "exporter", readOnly = false) BiConsumer<String, Map<String, Object>> exporter) {
            exporter = holder.get();
        }
    }
}
