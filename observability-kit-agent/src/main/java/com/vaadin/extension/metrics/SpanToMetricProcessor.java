package com.vaadin.extension.metrics;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

public class SpanToMetricProcessor implements SpanProcessor {

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        Long latencyMs = span.getLatencyNanos() / 1000000;
        Metrics.recordSpanDuration(span.getName(), latencyMs, span.getSpanContext());
    }

    @Override
    public void onStart(Context arg0, ReadWriteSpan arg1) {
      
    }


}
