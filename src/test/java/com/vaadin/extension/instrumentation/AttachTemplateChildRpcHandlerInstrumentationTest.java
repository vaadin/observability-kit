package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.flow.dom.Element;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

class AttachTemplateChildRpcHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    @Test
    public void attachElement_createsSpan() {
        Element attachedElement = new Element("attached-element");
        AttachTemplateChildRpcHandlerInstrumentation.AttachElementAdvice
                .onEnter(attachedElement.getNode(), null, null);
        AttachTemplateChildRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(null, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("AttachTemplateChild: attached-element", span.getName());
        assertEquals("attached-element", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.element.tag")));
    }
}
