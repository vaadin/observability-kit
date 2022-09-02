package com.vaadin.extension.instrumentation.server;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.metrics.Metrics;
import com.vaadin.flow.server.VaadinSession;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments VaadinSession to keep track of the number of current sessions and
 * UIs
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

        transformer.applyAdviceToMethod(named("addUI"),
                this.getClass().getName() + "$AddUiAdvice");
        transformer.applyAdviceToMethod(named("removeUI"),
                this.getClass().getName() + "$RemoveUiAdvice");
    }

    @SuppressWarnings("unused")
    public static class CreateSessionAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.This VaadinSession session) {
            Metrics.recordSessionStart(session);
        }
    }

    @SuppressWarnings("unused")
    public static class CloseSessionAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.This VaadinSession session) {
            Metrics.recordSessionEnd(session);
        }
    }

    @SuppressWarnings("unused")
    public static class AddUiAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit() {
            Metrics.incrementUiCount();
        }
    }

    @SuppressWarnings("unused")
    public static class RemoveUiAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit() {
            Metrics.decrementUiCount();
        }
    }
}
