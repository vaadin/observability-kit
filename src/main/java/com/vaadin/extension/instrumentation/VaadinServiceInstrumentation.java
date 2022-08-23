package com.vaadin.extension.instrumentation;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.ContextKeys;
import com.vaadin.flow.server.VaadinRequest;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments VaadinService to add the HTTP session id to the context, to be
 * retrieved by nested instrumentations
 */
public class VaadinServiceInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("com.vaadin.flow.server.VaadinService");
    }

    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.flow.server.VaadinService");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleRequest"),
                this.getClass().getName() + "$MethodAdvice");
    }

    @SuppressWarnings("unused")
    public static class MethodAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(@Advice.Argument(0) VaadinRequest request,
                @Advice.Local("otelScope") Scope scope) {

            String sessionId = request.getWrappedSession().getId();
            // Provide session id in context for nested instrumentations
            scope = Context.current().with(ContextKeys.SESSION_ID, sessionId)
                    .makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelScope") Scope scope) {
            if (scope == null) {
                return;
            }
            scope.close();
        }
    }
}
