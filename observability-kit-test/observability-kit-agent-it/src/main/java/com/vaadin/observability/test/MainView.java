package com.vaadin.observability.test;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.router.Route;

@Route("")
public class MainView extends Div {

    public MainView() {
        add(new H1("Observability Kit IT"));
    }

}
