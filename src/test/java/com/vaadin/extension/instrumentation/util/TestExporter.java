package com.vaadin.extension.instrumentation.util;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TestExporter implements SpanExporter {
    private final List<SpanData> spans = new ArrayList<>();

    public List<SpanData> getSpans() {
        return spans;
    }

    public SpanData getSpan(int index) {
        Assertions.assertTrue(index < spans.size(), "Not enough exported spans");
        return spans.get(index);
    }

    public void reset() {
        spans.clear();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        this.spans.addAll(spans);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
