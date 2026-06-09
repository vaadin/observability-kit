/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.starter;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.Route;

/**
 * Simple landing view; opening it drives a real Vaadin session, UI and
 * navigation through the framework so the Micrometer binders fire.
 */
@Route("")
public class HelloView extends Div {

    public HelloView() {
        Span greeting = new Span("Hello micrometer boot");
        greeting.setId("greeting");
        add(greeting);
    }
}
