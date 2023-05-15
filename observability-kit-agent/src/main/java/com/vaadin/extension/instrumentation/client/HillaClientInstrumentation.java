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

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.function.BiConsumer;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(isConstructor(),
            this.getClass().getName() + "$ConstructorAdvice");
    }

    public static class ConstructorAdvice {
        @Advice.OnMethodExit()
        public static void onExit(
            @Advice.FieldValue(value = "exporter", readOnly = false)
            BiConsumer<String, Map<String, Object>> exporter
        ) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
            exporter = (BiConsumer<String, Map<String, Object>>) Class.forName(
                "com.vaadin.extension.instrumentation.client.ObjectMapExporter").getDeclaredConstructor().newInstance();
        }
    }
}
