package com.vaadin.extension.instrumentation.client;

import java.util.logging.Logger;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@AutoService(AutoConfigurationCustomizerProvider.class)
public class ClientAutoConfigurationCustomizerProvider
        implements AutoConfigurationCustomizerProvider {
    private static final Logger logger =
            Logger.getLogger(ClientAutoConfigurationCustomizerProvider.class.getName());

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration
                .addSpanExporterCustomizer(this::configureSdkSpanExporter)
                .addTracerProviderCustomizer(this::configureSdkTracerProvider);
    }

    private SpanExporter configureSdkSpanExporter(SpanExporter exporter,
            ConfigProperties config) {
        logger.info("Exporter: " + exporter.getClass().getName());
        SpanExporterWrapper wrapper = SpanExporterWrapper.current();
        wrapper.setWrappedExporter(exporter);
        return wrapper;
    }

    private SdkTracerProviderBuilder configureSdkTracerProvider(
            SdkTracerProviderBuilder builder, ConfigProperties config) {
        return builder;
    }
}
