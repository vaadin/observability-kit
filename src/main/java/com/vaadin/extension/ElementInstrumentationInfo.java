package com.vaadin.extension;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.internal.StateTree;

/**
 * Provides common information about a Flow DOM node that can be used in
 * instrumentations
 */
public class ElementInstrumentationInfo {
    private final StateNode node;
    private final Element element;
    private final String elementLabel;
    private Component view;
    private String viewLabel;

    public StateNode getNode() {
        return node;
    }

    public Element getElement() {
        return element;
    }

    public String getElementLabel() {
        return elementLabel;
    }

    public Component getView() {
        return view;
    }

    public String getViewLabel() {
        return viewLabel;
    }

    /**
     * Creates an {@link ElementInstrumentationInfo} instance from an element's
     * state node
     *
     * @param node
     *            the state node for the element
     */
    public ElementInstrumentationInfo(StateNode node) {
        // Provide element info
        this.node = node;
        element = Element.get(node);

        String identifier = "";
        if (element.getText() != null && !element.getText().isEmpty()) {
            identifier = String.format("[%s]", element.getText());
        }
        elementLabel = element.getTag() + identifier;

        // If possible add info for active view class
        view = null;
        viewLabel = null;
        if (node.getOwner() instanceof StateTree) {
            view = (Component) ((StateTree) node.getOwner()).getUI()
                    .getInternals().getActiveRouterTargetsChain().get(0);
            viewLabel = view.getClass().getSimpleName();
        }
    }
}
