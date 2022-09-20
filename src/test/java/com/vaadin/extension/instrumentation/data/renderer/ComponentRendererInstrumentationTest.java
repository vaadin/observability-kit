package com.vaadin.extension.instrumentation.data.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

class ComponentRendererInstrumentationTest extends AbstractInstrumentationTest {
    @Test
    public void componentRenderer_createComponent_generatesSpan() {
        Component component = new Div();
        ComponentRendererInstrumentation.CreateComponentAdvice.onEnter(null,
                null);
        ComponentRendererInstrumentation.CreateComponentAdvice.onExit(null,
                component, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Component creation", span.getName());
        String componentClass = span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.component"));
        assertEquals("Div", componentClass);
    }

    @Tag("div")
    private class Div extends Component {
    }
}
