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
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments HierarchicalDataProvider to add a span for the duration of data
 * provider fetches for hierarchical data.
 */
public class HierarchicalDataProviderInstrumentation
        implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return implementsInterface(named(
                "com.vaadin.flow.data.provider.hierarchy.HierarchicalDataProvider"));
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("fetch"),
                this.getClass().getName() + "$FetchAdvice");
        transformer.applyAdviceToMethod(named("fetchChildren"),
                this.getClass().getName() + "$FetchChildrenAdvice");
    }

    @SuppressWarnings("unused")
    public static class FetchAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.This HierarchicalDataProvider dataProvider,
                @Advice.Argument(0) Query<?, ?> query,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            span = InstrumentationHelper
                    .startSpan("Hierarchical Data Provider Fetch");
            span.setAttribute("vaadin.dataprovider.type",
                    dataProvider.getClass().getCanonicalName());
            span.setAttribute("vaadin.dataprovider.limit", query.getLimit());
            span.setAttribute("vaadin.dataprovider.offset", query.getOffset());

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

    @SuppressWarnings("unused")
    public static class FetchChildrenAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(
                @Advice.This HierarchicalDataProvider dataProvider,
                @Advice.Argument(0) HierarchicalQuery<?, ?> query,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            span = InstrumentationHelper
                    .startSpan("Hierarchical Data Provider Fetch Children");
            span.setAttribute("vaadin.dataprovider.type",
                    dataProvider.getClass().getCanonicalName());
            span.setAttribute("vaadin.dataprovider.limit", query.getLimit());
            span.setAttribute("vaadin.dataprovider.offset", query.getOffset());

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
