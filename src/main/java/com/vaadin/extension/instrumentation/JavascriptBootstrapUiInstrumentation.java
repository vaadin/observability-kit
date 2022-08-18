package com.vaadin.extension.instrumentation;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.component.internal.JavaScriptBootstrapUI;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class JavascriptBootstrapUiInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.flow.component.internal.JavaScriptBootstrapUI");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                named("connectClient"),
                this.getClass().getName() + "$ConnectClientAdvice");

        transformer.applyAdviceToMethod(
                named("navigate")
                        .and(takesArguments(2))
                        .and(takesArgument(0, named("java.lang.String")))
                        .and(takesArgument(1, named("com.vaadin.flow.router.QueryParameters"))),
                this.getClass().getName() + "$NavigateAdvice");
    }

    @SuppressWarnings({"unused", "UnusedAssignment"})
    public static class ConnectClientAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.This JavaScriptBootstrapUI ui,
                                   @Advice.Local("otelSpan") Span span) {
            span = InstrumentationHelper.getTracer().spanBuilder("JavaScriptBootstrapUI.connectClient").startSpan();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.This JavaScriptBootstrapUI ui,
                                  @Advice.Local("otelSpan") Span span) {
            span.end();
            // Update route after navigation to display the new route
            InstrumentationHelper.updateHttpRoute(ui);
        }
    }

    @SuppressWarnings("unused")
    public static class NavigateAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.This JavaScriptBootstrapUI ui) {
            // Update route after navigation to display the new route
            InstrumentationHelper.updateHttpRoute(ui);
        }
    }
}