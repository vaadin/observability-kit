package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.flow.dom.Element;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttachTemplateChildRpcHandlerInstrumentationTest
        extends AbstractInstrumentationTest {

    Element attachedElement = new Element("attached-element");

    @BeforeEach
    public void setup() {
        attachedElement = new Element("attached-element");
    }

    @Test
    public void attachElement_createsSpan() {
        AttachTemplateChildRpcHandlerInstrumentation.AttachElementAdvice
                .onEnter(attachedElement.getNode(), null, null);
        AttachTemplateChildRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(null, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Attach template child: attached-element", span.getName());
        assertEquals("attached-element", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.element.tag")));
    }

    @Test
    public void attachElement_respectsTraceLevel() {
        configureTraceLevel(TraceLevel.MINIMUM);
        AttachTemplateChildRpcHandlerInstrumentation.AttachElementAdvice
                .onEnter(attachedElement.getNode(), null, null);
        AttachTemplateChildRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(null, getCapturedSpanOrNull(0), null);

        assertEquals(0, getExportedSpanCount());

        configureTraceLevel(TraceLevel.DEFAULT);
        resetSpans();
        AttachTemplateChildRpcHandlerInstrumentation.AttachElementAdvice
                .onEnter(attachedElement.getNode(), null, null);
        AttachTemplateChildRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(null, getCapturedSpanOrNull(0), null);

        assertEquals(1, getExportedSpanCount());

        configureTraceLevel(TraceLevel.MAXIMUM);
        resetSpans();
        AttachTemplateChildRpcHandlerInstrumentation.AttachElementAdvice
                .onEnter(attachedElement.getNode(), null, null);
        AttachTemplateChildRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(null, getCapturedSpanOrNull(0), null);

        assertEquals(1, getExportedSpanCount());
    }
}
