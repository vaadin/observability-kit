package com.vaadin.extension;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * Add span attributes from the VaadinRequest attributes map.
 */
public class AttributeGetter
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
