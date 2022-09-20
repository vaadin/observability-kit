package com.vaadin.extension.instrumentation.data.renderer;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.component.Component;

import static org.junit.jupiter.api.Assertions.*;

class ComponentRendererInstrumentationTest  extends
        AbstractInstrumentationTest {
    @Test
    public void fetchFromDataProvider_createsSpan() {
        Component component = Mockito.mock(Component.class);
        ComponentRendererInstrumentation.CreateComponentAdvice.onEnter(null, null);
        ComponentRendererInstrumentation.CreateComponentAdvice.onExit(null, component,
                getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Component creation", span.getName());
        String dataProviderType = span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.component"));
        assertNotNull(dataProviderType);
    }
}