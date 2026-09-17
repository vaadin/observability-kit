/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import org.jspecify.annotations.Nullable;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.dom.Element;

/**
 * Reads what a person sees of a component: the caption it is known by on
 * screen, and the value it currently holds.
 * <p>
 * The point is replayability. A class name says which widget <em>kind</em>
 * failed, which is rarely enough to find it: a view has five Buttons, and
 * "Locate component Button" sends the reader hunting. The caption names the
 * one, and the value of the fields touched before it says what state the
 * failure needed.
 * <p>
 * Both are application text that may be data-bound — a caption can be "Delete
 * Jane Doe" and a value is user input almost by definition — so both are read
 * only in development mode, where the reader is the developer sitting in front
 * of the application, and never enter a production payload that may be
 * forwarded. Length is capped regardless, since a value can be a whole
 * document.
 */
final class ComponentCaptions {

    /**
     * Longest caption kept. Long enough for a real button or field label, short
     * enough that a caption bound to a paragraph of data is cut.
     */
    static final int MAX_CAPTION_LENGTH = 60;

    /** Longest value kept, shorter than a caption: values are user input. */
    static final int MAX_VALUE_LENGTH = 40;

    private ComponentCaptions() {
    }

    /**
     * The caption a component is known by on screen, or {@code null} when it
     * has none. Tried in the order a reader would recognize it: the field
     * label, the accessible name, the component's own text, and finally the id,
     * which is not on screen but is what a developer greps for.
     * <p>
     * Only the component's <em>own</em> text is considered — {@link Element}'s
     * text is its immediate text nodes, not its descendants' — so a layout
     * wrapping the whole view is captionless rather than captioned with every
     * word on the page.
     *
     * @param component
     *            the component to name, may be {@code null}
     * @return the caption, or {@code null}
     */
    static @Nullable String captionOf(@Nullable Component component) {
        if (component == null) {
            return null;
        }
        try {
            Element element = component.getElement();
            String caption = firstNonBlank(
                    component instanceof HasLabel hasLabel ? hasLabel.getLabel()
                            : element.getProperty("label", null),
                    element.getAttribute("aria-label"),
                    element.getProperty("placeholder", null),
                    component instanceof HasText hasText ? hasText.getText()
                            : null,
                    element.getAttribute("title"),
                    component.getId().orElse(null));
            return InsightDetails.truncate(caption, MAX_CAPTION_LENGTH);
        } catch (RuntimeException e) {
            // Reading a caption is best-effort enrichment; a component whose
            // getter throws must not break the invocation being observed.
            return null;
        }
    }

    /**
     * The value a component currently holds, rendered for a replay step, or
     * {@code null} when it holds none.
     * <p>
     * Read from the component rather than from the synchronized property it
     * arrived in, because the two are not the same text: a {@code Select} of
     * strings synchronizes the item <em>key</em>, so the property says
     * {@code '2'} where the component says {@code 'Defective'}. Only the second
     * is a replay step anyone can follow.
     * <p>
     * Must therefore be read after the property change event has run, i.e. at
     * the end of the invocation and not at its start: until then the component
     * still holds the previous value.
     *
     * @param component
     *            the component to read, may be {@code null}
     * @return the value as short text, or {@code null}
     */
    static @Nullable String valueOf(@Nullable Component component) {
        if (!(component instanceof HasValue<?, ?> hasValue)) {
            return null;
        }
        try {
            Object value = hasValue.getValue();
            if (value == null) {
                return null;
            }
            return InsightDetails.truncate(blankToNull(String.valueOf(value)),
                    MAX_VALUE_LENGTH);
        } catch (RuntimeException e) {
            // As above: a getter of an application's own field implementation
            // is not allowed to break the observation.
            return null;
        }
    }

    private static @Nullable String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            String text = blankToNull(candidate);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /**
     * Collapses the whitespace of a candidate and rejects it when nothing is
     * left. Text read off an element carries the source formatting of the
     * template or builder it came from, which would otherwise put line breaks
     * in the middle of a replay step.
     */
    private static @Nullable String blankToNull(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String collapsed = text.strip().replaceAll("\\s+", " ");
        return collapsed.isEmpty() ? null : collapsed;
    }
}
