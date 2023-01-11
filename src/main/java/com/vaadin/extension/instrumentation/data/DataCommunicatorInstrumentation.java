/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.extension.instrumentation.data;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.data.provider.DataCommunicator;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments DataCommunicator to add a span for the duration of data provider
 * fetches
 */
public class DataCommunicatorInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.flow.data.provider.DataCommunicator");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("fetchFromProvider"),
                this.getClass().getName() + "$FetchAdvice");
    }

    @SuppressWarnings("unused")
    public static class FetchAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.This DataCommunicator dataCommunicator,
                @Advice.Argument(0) int offset, @Advice.Argument(1) int limit,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            span = InstrumentationHelper.startSpan("Data Provider Fetch");
            span.setAttribute("vaadin.dataprovider.type", dataCommunicator
                    .getDataProvider().getClass().getCanonicalName());
            span.setAttribute("vaadin.dataprovider.limit", limit);
            span.setAttribute("vaadin.dataprovider.offset", offset);

            Context context = currentContext().with(span);
            scope = context.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            InstrumentationHelper.endSpan(span, throwable, scope);
        }
    }
}
