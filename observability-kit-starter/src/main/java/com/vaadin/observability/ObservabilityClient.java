package com.vaadin.observability;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;

@NpmPackage(value = "lit", version = "")
@NpmPackage(value = "@opentelemetry/sdk-trace-web", version = "1.8.0")
@NpmPackage(value = "@opentelemetry/instrumentation", version = "0.35.0")
@NpmPackage(value = "@opentelemetry/instrumentation-document-load", version = "0.31.0")
@NpmPackage(value = "@opentelemetry/instrumentation-user-interaction", version = "0.32.0")
@NpmPackage(value = "@opentelemetry/instrumentation-xml-http-request", version = "0.34.0")
@NpmPackage(value = "@opentelemetry/instrumentation-long-task", version = "0.32.0")
@NpmPackage(value = "@opentelemetry/exporter-trace-otlp-http", version = "0.35.0")
@Tag("vaadin-observability-client")
@JsModule("./components/observability-client.ts")
public class ObservabilityClient extends Component {
}
