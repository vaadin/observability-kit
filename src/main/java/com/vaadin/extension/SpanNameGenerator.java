/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
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
