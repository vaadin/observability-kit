package com.vaadin.extension.instrumentation.client;

import java.util.ArrayList;
import java.util.Collection;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public class SpanExporterWrapper implements SpanExporter {
    private final static SpanExporterWrapper wrapper = new SpanExporterWrapper();

    public static SpanExporterWrapper current() {
        return wrapper;
    }

    private SpanExporter wrapped = null;

    public void setWrappedExporter(SpanExporter wrapped) {
        this.wrapped = wrapped;
    }

    public void export(JsonNode root) {
        Collection<SpanData> spans = new ArrayList<>();
        for (JsonNode resourceSpanNode : root.get("resourceSpans")) {
            JsonNode resourceNode = resourceSpanNode.get("resource");
            for (JsonNode scopeSpanNode : resourceSpanNode
                    .get("scopeSpans")) {
                JsonNode scopeNode = scopeSpanNode.get("scope");
                for (JsonNode spanNode : scopeSpanNode.get("spans")) {
                    SpanData spanData = new JsonNodeSpanWrapper(
                            resourceNode, scopeNode, spanNode);
                    spans.add(spanData);
                }
            }
        }
        export(spans);
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> collection) {
        return wrapped.export(collection);
    }

    @Override
    public CompletableResultCode flush() {
        return wrapped.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return wrapped.shutdown();
    }
}
