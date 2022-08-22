package com.vaadin.extension.instrumentation.util;

import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.Assertions;

import java.util.ArrayList;
import java.util.List;

public class SpanBuilderCapture {
    private final List<Span> spans = new ArrayList<>();

    public void capture(Span span) {
        spans.add(span);
    }

    public List<Span> getSpans() {
        return spans;
    }

    public Span getSpan(int index) {
        Assertions.assertTrue(index < spans.size(), "Not enough captured spans");
        return spans.get(index);
    }

    public void reset() {
        spans.clear();
    }
}
