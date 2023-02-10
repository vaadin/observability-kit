package com.vaadin.extension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.instrumentation.AbstractInstrumentationTest;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.TestInstantiationException;

import java.util.Properties;

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
        assertThat(getVersion(),
                startsWith(InstrumentationHelper.INSTRUMENTATION_VERSION));
    }

    private String getVersion() {
        Properties properties = new Properties();

        try {
            properties.load(Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream("observability-kit.properties"));
        } catch (Exception e) {
            throw new TestInstantiationException(
                    "Unable to read observability-kit.properties", e);
        }

        return properties.getProperty("observability-kit.version");
    }
}
