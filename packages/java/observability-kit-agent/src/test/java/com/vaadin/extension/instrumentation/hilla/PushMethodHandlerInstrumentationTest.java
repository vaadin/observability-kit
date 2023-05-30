package com.vaadin.extension.instrumentation.hilla;

import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.vaadin.extension.AbstractTypeInstrumentationTest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

public class PushMethodHandlerInstrumentationTest extends AbstractTypeInstrumentationTest {
    private static final String patchedClassName = "dev.hilla.push.PushMessageHandler";
    private static final String patchedMethodName = "handleBrowserSubscribe";
    private PushMessageHandlerInstrumentation instrumentation;

    @BeforeEach
    public void setUp() {
        instrumentation = new PushMessageHandlerInstrumentation();
    }

    @Test
    public void should_ApplyAdviceClass_When_TransformMethodCalled() {
        var typeTransformer = mock(TypeTransformer.class);
        instrumentation.transform(typeTransformer);
        verify(typeTransformer).applyAdviceToMethod(ArgumentMatchers.eq(ElementMatchers.named(patchedMethodName)),
            ArgumentMatchers.eq(PushMessageHandlerInstrumentation.SubscribeAdvice.class.getName()));
    }

    @Test
    public void should_CheckAdviceClass() {
        checkAdviceClass(PushMessageHandlerInstrumentation.SubscribeAdvice.class);
    }

    @Test
    public void should_GetCorrectClassName_When_ClassLoaderOptimizationMethodCalled() {
        try (var agentElementMatchers = mockStatic(AgentElementMatchers.class)) {
            instrumentation.classLoaderOptimization();
            agentElementMatchers.verify(
                () -> AgentElementMatchers.hasClassesNamed(ArgumentMatchers.eq(patchedClassName)));
        }
    }

    @Test
    public void should_GetCorrectClassName_When_TypeMatcherMethodCalled() {
        try (var elementMatchers = mockStatic(ElementMatchers.class)) {
            instrumentation.typeMatcher();
            elementMatchers.verify(() -> ElementMatchers.named(ArgumentMatchers.eq(patchedClassName)));
        }
    }
}
