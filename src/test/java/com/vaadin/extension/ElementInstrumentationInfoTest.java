package com.vaadin.extension;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.Tag;

import org.junit.jupiter.api.Test;

class ElementInstrumentationInfoTest {

    @Test
    public void elementLabel_withId() {
        TestComponent component = new TestComponent();
        component.setId("test-id");
        component.setLabel("test-label");
        component.setText("test-text");

        ElementInstrumentationInfo info = new ElementInstrumentationInfo(
                component.getElement().getNode());
        assertEquals("test-component[id='test-id']", info.getElementLabel());
    }

    @Test
    public void getIdentifier_withLabel() {
        TestComponent component = new TestComponent();
        component.setLabel("test-label");
        component.setText("test-text");

        ElementInstrumentationInfo info = new ElementInstrumentationInfo(
                component.getElement().getNode());
        assertEquals("test-component[label='test-label']",
                info.getElementLabel());
    }

    @Test
    public void getIdentifier_withText() {
        TestComponent component = new TestComponent();
        component.setText("test-text");

        ElementInstrumentationInfo info = new ElementInstrumentationInfo(
                component.getElement().getNode());
        assertEquals("test-component[test-text]", info.getElementLabel());
    }

    @Test
    public void getIdentifier_withTagName() {
        TestComponent component = new TestComponent();

        ElementInstrumentationInfo info = new ElementInstrumentationInfo(
                component.getElement().getNode());
        assertEquals("test-component", info.getElementLabel());
    }

    @Tag("test-component")
    private static class TestComponent extends Component
            implements HasLabel, HasText {
    }
}