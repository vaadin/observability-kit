package com.vaadin.observability;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;

@Tag("vaadin-observability-client")
@JsModule("./components/observability-client.ts")
public class ObservabilityClient extends Component {
}
