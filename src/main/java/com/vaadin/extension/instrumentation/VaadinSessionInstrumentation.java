package com.vaadin.extension.instrumentation;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.Metrics;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments VaadinSession to keep track of the number of current sessions
 */
public class VaadinSessionInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("com.vaadin.flow.server.VaadinSession");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.flow.server.VaadinSession");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("valueBound"),
                this.getClass().getName() + "$CreateSessionAdvice");
        transformer.applyAdviceToMethod(named("valueUnbound"),
                this.getClass().getName() + "$CloseSessionAdvice");
    }

    @SuppressWarnings("unused")
    public static class CreateSessionAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter() {
            Metrics.incrementSessionCount();
        }
    }

    @SuppressWarnings("unused")
    public static class CloseSessionAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter() {
            Metrics.decrementSessionCount();
        }
    }
}
