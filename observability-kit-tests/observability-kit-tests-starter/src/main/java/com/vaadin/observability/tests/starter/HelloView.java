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
import com.vaadin.flow.component.html.NativeButton;
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

        // One server round trip per click, for the browser-side interaction
        // timing ITs: the click is a UIDL request the collector must time, and
        // the response that changes the count is one it must see applied.
        Span clicks = new Span("0");
        clicks.setId("clicks");
        NativeButton bump = new NativeButton("Bump", event -> clicks.setText(
                String.valueOf(Integer.parseInt(clicks.getText()) + 1)));
        bump.setId("bump");
        add(bump, clicks);
    }
}
