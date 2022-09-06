package com.vaadin.extension.instrumentation.communication.rpc;

import static com.vaadin.extension.Constants.VIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.nodefeature.AttachExistingElementFeature;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttachExistingElementRpcHandlerInstrumentationTest
        extends AbstractInstrumentationTest {
    private Element attachedElement;
    private Element targetElement;
    private AttachExistingElementFeature feature;

    @BeforeEach
    public void setup() {
        attachedElement = new Element("attached-element");
        targetElement = new Element("target-element");
        targetElement.setText("foo");
        feature = targetElement.getNode()
                .getFeature(AttachExistingElementFeature.class);

        targetElement.appendChild(attachedElement);
        getMockUI().getElement().appendChild(targetElement);
    }

    @Test
    public void attachElement_createsSpan() {
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onEnter(feature, attachedElement.getNode(), null, null);
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(null, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Attach existing element: attached-element",
                span.getName());
        assertEquals("attached-element", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.element.tag")));
        assertEquals("target-element[foo]", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.element.target")));
        assertEquals("TestView",
                span.getAttributes().get(AttributeKey.stringKey(VIEW)));
    }

    @Test
    public void attachElementWithException_setsErrorStatus() {
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onEnter(feature, attachedElement.getNode(), null, null);
        Exception exception = new RuntimeException("test error");
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(exception, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        this.assertSpanHasException(span, exception);
    }

    @Test
    public void attachElement_respectsTraceLevel() {
        configureTraceLevel(TraceLevel.MINIMUM);
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onEnter(feature, attachedElement.getNode(), null, null);
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(null, getCapturedSpanOrNull(0), null);

        assertEquals(0, getExportedSpanCount());

        configureTraceLevel(TraceLevel.DEFAULT);
        resetSpans();
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onEnter(feature, attachedElement.getNode(), null, null);
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(null, getCapturedSpanOrNull(0), null);

        assertEquals(1, getExportedSpanCount());

        configureTraceLevel(TraceLevel.MAXIMUM);
        resetSpans();
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onEnter(feature, attachedElement.getNode(), null, null);
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(null, getCapturedSpanOrNull(0), null);

        assertEquals(1, getExportedSpanCount());
    }
}