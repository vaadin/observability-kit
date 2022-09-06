package com.vaadin.extension;

import io.opentelemetry.api.trace.SpanKind;

import java.util.Collections;
import java.util.Map;

public class InstrumentationRequest {
    private final String name;
    private final SpanKind spanKind;
    private final Map<String, String> attributes;

    public InstrumentationRequest(String name, SpanKind spanKind) {
        this(name, spanKind, Collections.emptyMap());
    }

    public InstrumentationRequest(String name, SpanKind spanKind,
            Map<String, String> attributes) {
        this.name = name;
        this.spanKind = spanKind;
        this.attributes = attributes;
    }

    public String getName() {
        return name;
    }

    public SpanKind getSpanKind() {
        return spanKind;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
