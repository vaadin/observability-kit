package com.example.application.views.client;

import com.example.application.views.MainLayout;
import jakarta.annotation.security.PermitAll;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.router.Route;

@Route(value = "client-components", layout = MainLayout.class)
@PermitAll
public class ClientComponentsView extends Div {

    public ClientComponentsView() {
        add(new NativeButton("Raise server-side Javascript error",
                event -> UI.getCurrent().getPage()
                        .executeJs("invokeNotExistingFunctionFromServer();")));
        add(new ProblematicComponent());
    }
}
