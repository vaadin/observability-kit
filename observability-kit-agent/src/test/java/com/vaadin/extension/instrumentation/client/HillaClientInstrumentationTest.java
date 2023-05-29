package com.vaadin.extension.instrumentation.client;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.vaadin.extension.AbstractTypeInstrumentationTest;

import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers;
import net.bytebuddy.matcher.ElementMatchers;

public class HillaClientInstrumentationTest
        extends AbstractTypeInstrumentationTest {
    private static final String targetClassName = "dev.hilla.observability.ObservabilityEndpoint";
    private HillaClientInstrumentation instrumentation;

    @BeforeEach
    public void setUp() {
        instrumentation = new HillaClientInstrumentation();
    }

    @Test
    public void should_ApplyAdviceClass_When_TransformMethodCalled() {
        var typeTransformer = mock(TypeTransformer.class);
        instrumentation.transform(typeTransformer);
        verify(typeTransformer).applyAdviceToMethod(
                ArgumentMatchers.eq(ElementMatchers.isTypeInitializer()),
                ArgumentMatchers
                        .eq(HillaClientInstrumentation.ExportMethodAdvice.class
                                .getName()));
    }

    @Test
    public void should_CheckAdviceClass() {
        checkAdviceClass(HillaClientInstrumentation.ExportMethodAdvice.class);
    }

    @Test
    public void should_GetCorrectClassName_When_ClassLoaderOptimizationMethodCalled() {
        try (var agentElementMatchers = mockStatic(
                AgentElementMatchers.class)) {
            instrumentation.classLoaderOptimization();
            agentElementMatchers.verify(() -> AgentElementMatchers
                    .hasClassesNamed(ArgumentMatchers.eq(targetClassName)));
        }
    }

    @Test
    public void should_GetCorrectClassName_When_TypeMatcherMethodCalled() {
        try (var elementMatchers = mockStatic(ElementMatchers.class)) {
            instrumentation.typeMatcher();
            elementMatchers.verify(() -> ElementMatchers
                    .named(ArgumentMatchers.eq(targetClassName)));
        }
    }
}
