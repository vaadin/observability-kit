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

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;

import java.util.Map;

public class SpanAttributeGenerator
        implements AttributesExtractor<InstrumentationRequest, Object> {

    @Override
    public void onStart(AttributesBuilder attributes, Context parentContext,
            InstrumentationRequest vaadinRequest) {
        final Map<String, String> attributesMap = vaadinRequest.getAttributes();
        for (Map.Entry<String, String> entry : attributesMap.entrySet()) {
            attributes.put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void onEnd(AttributesBuilder attributes, Context context,
            InstrumentationRequest vaadinRequest, Object unused,
            Throwable error) {

    }
}
