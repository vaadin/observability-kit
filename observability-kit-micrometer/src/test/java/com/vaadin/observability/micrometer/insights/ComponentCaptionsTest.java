/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.dom.ElementFactory;

class ComponentCaptionsTest {

    /** A component whose caption is its own text, the shape of a Button. */
    private static class TextComponent extends Component implements HasText {
        TextComponent(Element element) {
            super(element);
        }
    }

    private static TextComponent button(String text) {
        return new TextComponent(ElementFactory.createButton(text));
    }

    @Test
    void noComponentNoCaption() {
        Assertions.assertNull(ComponentCaptions.captionOf(null));
        Assertions.assertNull(ComponentCaptions.valueOf(null));
    }

    @Test
    void ownTextIsTheCaptionOfAButton() {
        Assertions.assertEquals("Process return",
                ComponentCaptions.captionOf(button("Process return")));
    }

    @Test
    void labelWinsOverTextSoAFieldIsNamedByItsLabel() {
        TextComponent component = button("some inner text");
        component.getElement().setProperty("label", "Reason");

        Assertions.assertEquals("Reason",
                ComponentCaptions.captionOf(component));
    }

    @Test
    void accessibleNameNamesAnIconOnlyControl() {
        TextComponent component = button("");
        component.getElement().setAttribute("aria-label", "Close dialog");

        Assertions.assertEquals("Close dialog",
                ComponentCaptions.captionOf(component));
    }

    @Test
    void idIsTheLastResortBecauseItIsWhatAdeveloperGreps() {
        TextComponent component = button("");
        component.setId("return-reason");

        Assertions.assertEquals("return-reason",
                ComponentCaptions.captionOf(component));
    }

    @Test
    void aLayoutIsCaptionlessRatherThanNamedAfterThePageItWraps() {
        // Only the component's own text nodes count, so a wrapper is not
        // captioned with everything its children say.
        Element layout = ElementFactory.createDiv();
        layout.appendChild(ElementFactory.createSpan("a whole view of text"));

        Assertions.assertNull(
                ComponentCaptions.captionOf(new TextComponent(layout)));
    }

    @Test
    void whitespaceIsCollapsedSoAStepStaysOneLine() {
        Assertions.assertEquals("Process return",
                ComponentCaptions.captionOf(button("  Process\n  return  ")));
    }

    @Test
    void aCaptionBoundToAParagraphOfDataIsCut() {
        String caption = ComponentCaptions.captionOf(button("x".repeat(500)));

        Assertions.assertEquals(ComponentCaptions.MAX_CAPTION_LENGTH + 1,
                caption.length(), "the cut should be visible in the value");
        Assertions.assertTrue(caption.endsWith("…"));
    }
}
