package com.vaadin.extension.instrumentation.server;

import static com.vaadin.extension.InstrumentationHelper.INSTRUMENTER;
import static com.vaadin.extension.InstrumentationHelper.handleException;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.InstrumentationRequest;
import com.vaadin.flow.server.ErrorEvent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments VaadinService to add the HTTP session id to the context, to be
 * retrieved by nested instrumentations.
 * <p>
 * Also sets the application RootSpan as this is the in point for handling the
 * incoming requests.
 */
public class ErrorHandlerInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("com.vaadin.flow.server.ErrorHandler");
    }

    public ElementMatcher<TypeDescription> typeMatcher() {
        return implementsInterface(
                named("com.vaadin.flow.server.VaadinService"));
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("error"),
                this.getClass().getName() + "$ErrorAdvice");
    }

    @SuppressWarnings("unused")
    public static class ErrorAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(@Advice.Argument(0) ErrorEvent event,
                @Advice.Local("otelContext") Context context,
                @Advice.Local("otelScope") Scope scope) {
            // Using instrumentation to get this as LocalRootSpan!
            InstrumentationRequest request = new InstrumentationRequest(
                    "Internal Error", SpanKind.SERVER);

            context = INSTRUMENTER.start(currentContext(), request);
            scope = context.makeCurrent();
            handleException(Span.fromContextOrNull(context),
                    event.getThrowable());
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelContext") Context context,
                @Advice.Local("otelScope") Scope scope) {
            if (scope == null) {
                return;
            }
            scope.close();

            INSTRUMENTER.end(context, null, null, throwable);
        }
    }
}
