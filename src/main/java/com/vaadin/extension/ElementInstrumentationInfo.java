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
    private StateNode node;
    private Element element;
    private String elementLabel;
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
     * @return the information about the element
     */
    public static ElementInstrumentationInfo create(StateNode node) {
        // Provide element info
        Element element = Element.get(node);

        String identifier = "";
        if (element.getText() != null && !element.getText().isEmpty()) {
            identifier = String.format("[%s]", element.getText());
        }
        String elementLabel = element.getTag() + identifier;

        // If possible add info for active view class
        Component view = null;
        String viewLabel = null;
        if (node.getOwner() instanceof StateTree) {
            view = (Component) ((StateTree) node.getOwner()).getUI()
                    .getInternals().getActiveRouterTargetsChain().get(0);
            viewLabel = view.getClass().getSimpleName();
        }

        // Construct and return result
        ElementInstrumentationInfo info = new ElementInstrumentationInfo();
        info.node = node;
        info.element = element;
        info.elementLabel = elementLabel;
        info.view = view;
        info.viewLabel = viewLabel;

        return info;
    }
}
