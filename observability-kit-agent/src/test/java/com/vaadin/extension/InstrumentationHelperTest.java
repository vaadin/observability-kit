package com.vaadin.extension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.text.MatchesPattern.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

class InstrumentationHelperTest extends AbstractInstrumentationTest {

    @Test
    public void handleException() {
        Exception exception = new RuntimeException("Something went wrong");
        try (var ignored = withRootContext()) {
            Span span = InstrumentationHelper.startSpan("Test span");
            InstrumentationHelper.handleException(span, exception);
            span.end();
        }

        // Should record exception as event on span
        SpanData testSpan = getExportedSpan(0);
        assertEquals("Test span", testSpan.getName());
        assertSpanHasException(testSpan, exception);
    }

    @Test
    public void assertInstrumentationVersionSet() {
        assertThat(InstrumentationHelper.VERSION,
                matchesPattern("^\\d\\.\\d.*"));
    }
}