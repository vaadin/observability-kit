package com.vaadin.extension.instrumentation;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.component.internal.JavaScriptBootstrapUI;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.named;

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
            // Update route after initial routing / rendering of UI due to a client-side navigation or page load
            InstrumentationHelper.updateHttpRoute(ui);
        }
    }
}