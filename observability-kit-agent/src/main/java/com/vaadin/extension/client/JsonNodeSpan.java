package com.vaadin.extension.client;

import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.jetbrains.annotations.Nullable;

public class JsonNodeSpan implements ReadWriteSpan {
    @Override
    public <T> Span setAttribute(AttributeKey<T> key, T value) {
        return null;
    }

    @Override
    public Span addEvent(String s, Attributes attributes) {
        return null;
    }

    @Override
    public Span addEvent(String s, Attributes attributes, long l, TimeUnit timeUnit) {
        return null;
    }

    @Override
    public Span setStatus(StatusCode statusCode, String s) {
        return null;
    }

    @Override
    public Span recordException(Throwable throwable, Attributes attributes) {
        return null;
    }

    @Override
    public Span updateName(String s) {
        return null;
    }

    @Override
    public void end() {

    }

    @Override
    public void end(long l, TimeUnit timeUnit) {

    }

    @Override
    public SpanContext getSpanContext() {
        return null;
    }

    @Override
    public SpanContext getParentSpanContext() {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public SpanData toSpanData() {
        return null;
    }

    @Override
    public InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
        return null;
    }

    @Override
    public boolean hasEnded() {
        return false;
    }

    @Override
    public long getLatencyNanos() {
        return 0;
    }

    @Override
    public SpanKind getKind() {
        return null;
    }

    @Nullable
    @Override
    public <T> T getAttribute(AttributeKey<T> attributeKey) {
        return null;
    }

    @Override
    public boolean isRecording() {
        return false;
    }
}
