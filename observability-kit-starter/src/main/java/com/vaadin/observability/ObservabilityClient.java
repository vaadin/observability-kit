package com.vaadin.observability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.internal.JsonUtils;

import elemental.json.JsonArray;
import elemental.json.JsonValue;

@NpmPackage(value = "@opentelemetry/sdk-trace-web", version = "1.8.0")
@NpmPackage(value = "@opentelemetry/instrumentation", version = "0.35.0")
@NpmPackage(value = "@opentelemetry/instrumentation-document-load", version = "0.31.0")
@NpmPackage(value = "@opentelemetry/instrumentation-user-interaction", version = "0.32.0")
@NpmPackage(value = "@opentelemetry/instrumentation-xml-http-request", version = "0.34.0")
@NpmPackage(value = "@opentelemetry/instrumentation-long-task", version = "0.32.0")
@NpmPackage(value = "@opentelemetry/exporter-trace-otlp-http", version = "0.35.0")
@Tag("vaadin-observability-client")
@JsModule("./components/observability-client.ts")
class ObservabilityClient extends Component
        implements ObservabilityClientConfiguration {

    boolean active = true;

    public ObservabilityClient(String identifier) {
        getElement().setProperty("instanceId",
                Objects.requireNonNull(identifier));
        // defaults
        traceDocumentLoad(true);
        traceXMLHttpRequests(true);
        traceLongTask(true);
        traceErrors(true);
        traceUserInteraction(true);
    }

    @Override
    public void active(boolean active) {
        this.active = active;
    }

    @Override
    public void traceDocumentLoad(boolean active) {
        getElement().setProperty("traceDocumentLoad", active);
    }

    @Override
    public void traceUserInteraction(boolean active) {
        if (active) {
            getElement().setPropertyList("traceUserInteraction",
                    Collections.singletonList("click"));
        } else {
            getElement().removeProperty("traceUserInteraction");
        }
    }

    @Override
    public void traceUserInteraction(Set<String> events) {
        getElement().setPropertyList("traceUserInteraction",
                new ArrayList<>(events));
    }

    @Override
    public void traceLongTask(boolean active) {
        getElement().setProperty("traceLongTask", active);
    }

    @Override
    public void traceXMLHttpRequests(boolean active) {
        getElement().setProperty("traceXmlHTTPRequest", active);
    }

    @Override
    public void ignoreVaadinUrls(boolean active) {
        getElement().setProperty("ignoreVaadinURLs", active);
    }

    @Override
    public void ignoreUrls(Collection<String> urls) {
        appendIgnoredUrls(urls);
    }

    @Override
    public void ignoreUrlsByPattern(Collection<String> urlRegex) {
        appendIgnoredUrls(urlRegex.stream().map(re -> "RE:/" + re + "/")
                .collect(Collectors.toSet()));

    }

    private void appendIgnoredUrls(Collection<String> urls) {
        Set<String> ignoredUrls = new LinkedHashSet<>();
        if (getElement().hasProperty("ignoredURLs")) {
            JsonUtils.stream(
                    (JsonArray) getElement().getPropertyRaw("ignoredURLs"))
                    .map(JsonValue::asString).forEach(ignoredUrls::add);
        }
        ignoredUrls.addAll(urls);
        getElement().setPropertyList("ignoredURLs",
                new ArrayList<>(ignoredUrls));
    }

    @Override
    public void traceErrors(boolean active) {
        getElement().setProperty("traceErrors", active);
    }

}
