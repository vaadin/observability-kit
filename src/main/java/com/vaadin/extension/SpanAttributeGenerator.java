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

import jakarta.annotation.Nullable;

public class SpanAttributeGenerator
        implements AttributesExtractor<InstrumentationRequest, Object> {

    @Override
    public void onStart(AttributesBuilder attributes, Context parentContext,
            InstrumentationRequest vaadinRequest) {
        final Map<String, String> attributesMap = vaadinRequest.getAttributes();
        for (String key : attributesMap.keySet()) {
            attributes.put(key, attributesMap.get(key));
        }
    }

    @Override
    public void onEnd(AttributesBuilder attributes, Context context,
            InstrumentationRequest vaadinRequest, @Nullable Object unused,
            @Nullable Throwable error) {

    }
}
