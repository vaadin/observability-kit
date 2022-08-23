package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.nodefeature.AttachExistingElementFeature;
import com.vaadin.flow.server.communication.rpc.AttachExistingElementRpcHandler;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AttachExistingElementRpcHandlerInstrumentationTest
        extends AbstractInstrumentationTest {
    private AttachExistingElementRpcHandler attachExistingElementRpcHandler;
    private Element attachedElement;
    private Element targetElement;
    private AttachExistingElementFeature feature;

    @BeforeEach
    public void setup() {
        attachExistingElementRpcHandler = Mockito
                .mock(AttachExistingElementRpcHandler.class);
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
                .onEnter(attachExistingElementRpcHandler, "attachElement",
                        feature, attachedElement.getNode(), null);
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(null, getCapturedSpan(0));

        SpanData span = getExportedSpan(0);
        assertEquals("Attach existing element: attached-element",
                span.getName());
        assertEquals("attached-element", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.element.tag")));
        assertEquals("target-element[foo]", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.element.target")));
        assertEquals("TestView", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.view")));
    }

    @Test
    public void attachElementWithException_setsErrorStatus() {
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onEnter(attachExistingElementRpcHandler, "attachElement",
                        feature, attachedElement.getNode(), null);
        Exception exception = new RuntimeException("test error");
        AttachExistingElementRpcHandlerInstrumentation.AttachElementAdvice
                .onExit(exception, getCapturedSpan(0));

        SpanData span = getExportedSpan(0);
        this.assertSpanHasException(span, exception);
    }
}