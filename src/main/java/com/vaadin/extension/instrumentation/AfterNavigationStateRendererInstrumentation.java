package com.vaadin.extension.instrumentation;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.router.NavigationEvent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Optional;

/**
 * This instrumentation captures all navigation operations, and updates the root
 * span to contain the new route, rather than the route where the request
 * initiated from. Also adds a nested span with more information about the
 * navigation operation.
 */
public class AfterNavigationStateRendererInstrumentation
        implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.router.internal.AbstractNavigationStateRenderer");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handle"),
                this.getClass().getName() + "$HandleAdvice");
    }

    @SuppressWarnings("unused")
    public static class HandleAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.Argument(0) NavigationEvent event,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            span = InstrumentationHelper.startSpan("Navigate");

            Context context = currentContext().with(span);
            scope = context.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Argument(0) NavigationEvent event,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            // Seems UI only contains the correct route after the method
            // finishes, so this needs to run on exit
            Optional<String> routeTemplate = InstrumentationHelper
                    .getActiveRouteTemplate(event.getUI());
            if (routeTemplate.isPresent()) {
                String route = "/" + routeTemplate.get();
                span.updateName(String.format("Navigate: %s", route));
                span.setAttribute("vaadin.navigation.route", route);
            }
            span.setAttribute("vaadin.navigation.isForwardTo",
                    event.isForwardTo());
            span.setAttribute("vaadin.navigation.trigger",
                    event.getTrigger().name());

            InstrumentationHelper.endSpan(span, throwable, scope);
            // Update route after navigation to display the new route
            InstrumentationHelper.updateHttpRoute(event.getUI());
        }
    }
}
