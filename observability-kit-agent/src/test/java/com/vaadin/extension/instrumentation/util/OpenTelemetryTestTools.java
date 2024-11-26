package com.vaadin.extension.instrumentation.util;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.mockito.Mockito;

import static io.opentelemetry.semconv.ResourceAttributes.SERVICE_NAME;

public class OpenTelemetryTestTools {

    private static TestExporter spanExporter;
    private static TestMetricReader metricReader;
    private static SpanBuilderCapture spanBuilderCapture;
    private static OpenTelemetrySdk openTelemetry;

    public static TestExporter getSpanExporter() {
        return spanExporter;
    }

    public static TestMetricReader getMetricReader() {
        return metricReader;
    }

    public static SpanBuilderCapture getSpanBuilderCapture() {
        return spanBuilderCapture;
    }

    public static OpenTelemetrySdk getOpenTelemetry() {
        return openTelemetry;
    }

    public static void ensureSetup() {
        if (openTelemetry != null) {
            return;
        }

        spanExporter = new TestExporter();
        spanBuilderCapture = new SpanBuilderCapture();

        Resource resource = Resource.getDefault().merge(
                Resource.create(Attributes.of(SERVICE_NAME,
                        "test-service-name")));

        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .setResource(resource).build();
        SdkTracerProvider sdkTracerProviderSpy = Mockito.spy(sdkTracerProvider);
        Mockito.when(sdkTracerProviderSpy.get(Mockito.anyString(),
                Mockito.anyString())).thenAnswer(invocation -> {
                    Tracer tracer = (Tracer) invocation.callRealMethod();
                    Tracer tracerSpy = Mockito.spy(tracer);

                    Mockito.when(tracerSpy.spanBuilder(Mockito.anyString()))
                            .thenAnswer(spanBuilderInvocation -> {
                                SpanBuilder spanBuilder = (SpanBuilder) spanBuilderInvocation
                                        .callRealMethod();
                                SpanBuilder spanBuilderSpy = Mockito
                                        .spy(spanBuilder);

                                Mockito.when(spanBuilderSpy.startSpan())
                                        .thenAnswer(startSpanInvocation -> {
                                            Span span = (Span) startSpanInvocation
                                                    .callRealMethod();
                                            spanBuilderCapture.capture(span);
                                            return span;
                                        });

                                return spanBuilderSpy;
                            });

                    return tracerSpy;
                });

        metricReader = new TestMetricReader();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(metricReader.getMetricReader()).build();

        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProviderSpy)
                .setMeterProvider(meterProvider)
                .setPropagators(ContextPropagators
                        .create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();
    }
}
