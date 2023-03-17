package com.vaadin.observability.test;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dependency.JavaScript;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.router.Route;

@Route("")
public class MainView extends Div {

    public MainView() {
        add(new H1("Observability Kit IT"));
        NativeButton clientSideError = new NativeButton("Client side errors",
                ev -> {
                    UI.getCurrent().getPage()
                            .addJavaScript("module_with_error.js");
                    UI.getCurrent().getPage().executeJs(
                            "invokeNotExistingFunctionFromServer();");
                });
        clientSideError.setId("clientSideError");
        add(clientSideError);
        getElement().executeJs("breakMe();");
    }
}
