package com.vaadin.extension.instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.router.NavigationEvent;

import io.opentelemetry.api.trace.Span;
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
                @Advice.Local("otelSpan") Span span) {
            span = InstrumentationHelper.getTracer().spanBuilder("Navigate")
                    .startSpan();
            span.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Argument(0) NavigationEvent event,
                @Advice.Local("otelSpan") Span span) {
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
            span.end();
            // Update route after navigation to display the new route
            InstrumentationHelper.updateHttpRoute(event.getUI());
        }
    }
}
