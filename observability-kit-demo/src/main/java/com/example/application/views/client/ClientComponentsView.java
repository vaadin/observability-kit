package com.example.application.views.client;

import com.example.application.views.MainLayout;
import jakarta.annotation.security.PermitAll;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.Route;

@Route(value = "client-components", layout = MainLayout.class)
@PermitAll
public class ClientComponentsView extends Div {

    public ClientComponentsView() {
        add(new ProblematicComponent());
    }
}
