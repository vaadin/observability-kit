/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.tests;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.Route;

/**
 * Landing view; navigating to it drives session/UI/navigation/request lifecycle
 * through Flow so the micrometer binders fire.
 */
@Route("")
public class HelloView extends Div {

    public HelloView() {
        Span greeting = new Span("Hello micrometer spring");
        greeting.setId("greeting");
        add(greeting);
    }
}
