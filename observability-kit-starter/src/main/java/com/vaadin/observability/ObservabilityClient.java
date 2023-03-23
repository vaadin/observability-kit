package com.vaadin.observability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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

    boolean enabled = true;

    public ObservabilityClient(String identifier) {
        getElement().setProperty("instanceId",
                Objects.requireNonNull(identifier));
        // defaults
        setDocumentLoadEnabled(true);
        setXMLHttpRequestEnabled(true);
        setLongTaskEnabled(true);
        setFrontendErrorEnabled(true);
        setUserInteractionEnabled(true);
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setDocumentLoadEnabled(boolean enabled) {
        getElement().setProperty("traceDocumentLoad", enabled);
    }

    @Override
    public boolean isDocumentLoadEnabled() {
        return getElement().getProperty("traceDocumentLoad", false);
    }

    @Override
    public void setUserInteractionEnabled(boolean enabled) {
        if (!enabled) {
            getElement().removeProperty("traceUserInteraction");
        } else if (!isUserInteractionEnabled()) {
            setUserInteractionEvents("click");
        }
    }

    @Override
    public boolean isUserInteractionEnabled() {
        return getElement().hasProperty("traceUserInteraction");
    }

    @Override
    public void setUserInteractionEvents(Collection<String> events) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one event must be configured for UserInteraction instrumentation");
        }
        ArrayList<String> copy = new ArrayList<>(events);
        copy.removeIf(Objects::isNull);
        getElement().setPropertyList("traceUserInteraction", copy);
    }

    @Override
    public Set<String> getUserInteractionEvents() {
        return JsonUtils.stream(
                (JsonArray) getElement().getPropertyRaw("traceUserInteraction"))
                .map(JsonValue::asString).collect(Collectors.toSet());
    }

    @Override
    public void setLongTaskEnabled(boolean enabled) {
        getElement().setProperty("traceLongTask", enabled);
    }

    @Override
    public boolean isLongTaskEnabled() {
        return getElement().getProperty("traceLongTask", false);
    }

    @Override
    public void setXMLHttpRequestEnabled(boolean enabled) {
        getElement().setProperty("traceXmlHTTPRequest", enabled);
    }

    @Override
    public boolean isXMLHttpRequestEnabled() {
        return getElement().getProperty("traceXmlHTTPRequest", false);
    }

    @Override
    public void ignoreVaadinURLs(boolean ignore) {
        getElement().setProperty("ignoreVaadinURLs", ignore);
    }

    @Override
    public boolean isVaadinURLsIgnored() {
        return getElement().getProperty("ignoreVaadinURLs", false);
    }

    @Override
    public void setIgnoredURLs(Collection<URLPattern> urls) {
        getElement().setPropertyList("ignoredURLs",
                urls.stream().map(URLPattern::toInternalFormat)
                        .collect(Collectors.toList()));
    }

    @Override
    public List<URLPattern> getIgnoredURLs() {
        if (getElement().hasProperty("ignoredURLs")) {
            return JsonUtils
                    .stream((JsonArray) getElement()
                            .getPropertyRaw("ignoredURLs"))
                    .map(JsonValue::asString)
                    .map(URLPattern::fromInternalFormat)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public void addIgnoredURL(URLPattern url) {
        List<URLPattern> ignoredUrls = new ArrayList<>(getIgnoredURLs());
        ignoredUrls.add(url);
        setIgnoredURLs(ignoredUrls);
    }

    @Override
    public void setFrontendErrorEnabled(boolean enabled) {
        getElement().setProperty("traceErrors", enabled);
    }

    @Override
    public boolean isFrontendErrorEnabled() {
        return getElement().getProperty("traceErrors", false);
    }
}
