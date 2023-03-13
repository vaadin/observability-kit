package com.example.application.views.client;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;

@JsModule("./src/problematic-component.ts")
@Tag("problematic-component")
public class ProblematicComponent extends Component {
}
