package com.vaadin.extension;

import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor;

/**
 * Generate the span name from the VaadinRequest object name field.
 */
public class SpanNameGenerator
        implements SpanNameExtractor<InstrumentationRequest> {

    @Override
    public String extract(InstrumentationRequest vaadinRequest) {
        return vaadinRequest.getName();
    }
}