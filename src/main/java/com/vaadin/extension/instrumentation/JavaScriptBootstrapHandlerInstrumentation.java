package com.vaadin.extension.instrumentation;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.shared.ApplicationConstants;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpRouteHolder;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpRouteSource;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments JavaScriptBootstrapHandler to add a span for its execution, and
 * update the http route to contain the route template for the actual location
 * in the browser.
 */
public class JavaScriptBootstrapHandlerInstrumentation
        implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.JavaScriptBootstrapHandler");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.JavaScriptBootstrapHandler");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("synchronizedHandleRequest"),
                this.getClass().getName() + "$SynchronizedHandleRequestAdvice");
    }

    @SuppressWarnings("unused")
    public static class SynchronizedHandleRequestAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.Argument(1) VaadinRequest request,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            span = InstrumentationHelper.getTracer()
                    .spanBuilder("JavaScript Bootstrap").startSpan();

            // Rewrite root span to contain route, as bootstrap request is
            // always against application root with a location parameter
            Span localRootSpan = LocalRootSpan
                    .fromContextOrNull(Context.current());
            if (localRootSpan != null) {
                String location = request.getParameter(
                        ApplicationConstants.REQUEST_LOCATION_PARAMETER);
                String route = "/" + InstrumentationHelper
                        .getRouteTemplateForLocation(location).orElse("");

                // Need to use HttpRouteHolder.updateHttpRoute here, it seems
                // otherwise this gets overwritten by another instrumentation
                // later on
                HttpRouteHolder.updateHttpRoute(Context.current(),
                        HttpRouteSource.NESTED_CONTROLLER, route);
                String rootSpanName = route + " : JavaScript Bootstrap";
                localRootSpan.updateName(rootSpanName);
            }

            Context context = currentContext().with(span);
            scope = context.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            if (scope != null) {
                scope.close();
            }
            if (span == null) {
                return;
            }
            InstrumentationHelper.handleException(span, throwable);

            span.end();
        }
    }
}
