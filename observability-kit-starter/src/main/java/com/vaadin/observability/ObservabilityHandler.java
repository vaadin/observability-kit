package com.vaadin.observability;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.javaagent.bootstrap.internal.InstrumentationConfig;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.SynchronizedRequestHandler;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.ApplicationConstants;

public class ObservabilityHandler extends SynchronizedRequestHandler {
    private static Logger getLogger() {
        return LoggerFactory.getLogger(ObservabilityHandler.class);
    }

    private static final String PATH = "/";
    private static final String ID_PARAMETER = "id";
    private static final String REQUEST_TYPE = "o11y";
    private static final String HTTP_METHOD = "POST";
    private static final String CONTENT_TYPE = "application/json";

    private final String id = UUID.randomUUID().toString();

    static ObservabilityHandler ensureInstalled(UI ui) {
        ObservabilityHandler observabilityHandler = ComponentUtil.getData(ui,
                ObservabilityHandler.class);
        if (observabilityHandler != null) {
            // Already installed, return the existing handler
            return observabilityHandler;
        }

        ObservabilityHandler newObservabilityHandler =
                new ObservabilityHandler();

        VaadinSession session = ui.getSession();
        session.addRequestHandler(newObservabilityHandler);
        ComponentUtil.setData(ui, ObservabilityHandler.class,
                newObservabilityHandler);

        ui.addAttachListener(attachEvent -> {
            UI attachUI = attachEvent.getUI();
            VaadinSession attachSession = attachEvent.getSession();
            if (ComponentUtil.getData(attachUI, ObservabilityHandler.class) == null) {
                attachSession.addRequestHandler(newObservabilityHandler);
                ComponentUtil.setData(attachUI, ObservabilityHandler.class,
                        newObservabilityHandler);
            }
        });
        ui.addDetachListener(detachEvent -> {
            UI detachUI = detachEvent.getUI();
            VaadinSession detachSession = detachEvent.getSession();
            if (ComponentUtil.getData(detachUI, ObservabilityHandler.class) != null) {
                detachSession.removeRequestHandler(newObservabilityHandler);
                ComponentUtil.setData(detachUI, ObservabilityHandler.class, null);
            }
        });
        return newObservabilityHandler;
    }

    private final InstrumentationConfig config;
    private SpanExporter exporter = null;

    public ObservabilityHandler() {
        this.config = InstrumentationConfig.get();
        String exporterType = config.getString("otel.traces.exporter", "otlp");
        if ("logging".equals(exporterType)) {
            this.exporter = LoggingSpanExporter.create();
        } else if ("logging-otlp".equals(exporterType)) {
            this.exporter = OtlpJsonLoggingSpanExporter.create();
        } else if ("otlp".equals(exporterType)) {

            String protocol = config.getString(
                    "otel.exporter.otlp.traces.protocol",
                    config.getString(
                            "otel.exporter.otlp.protocol",
                            "grpc"
                    ));
            String endpoint = config.getString(
                    "otel.exporter.otlp.traces.endpoint",
                    config.getString(
                            "otel.exporter.otlp.endpoint",
                            "http://localhost:4317"
                    )
            );
            if ("http/protobuf".equals(protocol)) {
                OtlpHttpSpanExporterBuilder builder =
                        OtlpHttpSpanExporter.builder()
                                .setEndpoint(endpoint);

                Map<String, String> headers = config.getMap(
                        "otel.exporter.otlp.trace.headers",
                        config.getMap(
                                "otel.exporter.otlp.headers",
                                Collections.emptyMap()
                        )
                );
                headers.forEach(builder::addHeader);

                this.exporter = builder.build();
            } else if ("grpc".equals(protocol)) {
                OtlpGrpcSpanExporterBuilder builder =
                        OtlpGrpcSpanExporter.builder()
                                .setEndpoint(endpoint);

                Map<String, String> headers = config.getMap(
                        "otel.exporter.otlp.trace.headers",
                        config.getMap(
                                "otel.exporter.otlp.headers",
                                Collections.emptyMap()
                        )
                );
                headers.forEach(builder::addHeader);

                this.exporter = builder.build();
            }
        } else if ("jaeger".equals(exporterType)) {
            String endpoint = config.getString(
                    "otel.exporter.jaeger.endpoint",
                    "http://localhost:14250"
            );
            long timeout = config.getLong(
                    "otel.exporter.jaeger.timeout",
                    10000L
            );
            this.exporter = JaegerGrpcSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .setTimeout(timeout, TimeUnit.MILLISECONDS)
                    .build();
        }
    }

    @Override
    protected boolean canHandleRequest(VaadinRequest request) {
        if (!PATH.equals(request.getPathInfo())) {
            return false;
        }
        String requestType = request
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER);
        String instanceId = request.getParameter(ID_PARAMETER);
        return REQUEST_TYPE.equals(requestType) && id.equals(instanceId);
    }

    @Override
    public boolean synchronizedHandleRequest(VaadinSession session,
            VaadinRequest request, VaadinResponse response) {
        if (!HTTP_METHOD.equals(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return true;
        }
        if (!CONTENT_TYPE.equals(request.getContentType())) {
            response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
            return true;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(request.getInputStream());

            if (!root.has("resourceSpans")) {
                getLogger().error("Malformed traces message.");
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return true;
            }

            export(root);

            response.setStatus(HttpServletResponse.SC_OK);
        } catch (IOException e) {
            getLogger().error("Exception when processing traces.");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }

        return true;
    }

    private void export(JsonNode root) {
        if (exporter == null) {
            return;
        }

        Collection<SpanData> spans = new ArrayList<>();
        for (JsonNode resourceSpanNode : root.get("resourceSpans")) {
            JsonNode resourceNode = resourceSpanNode.get("resource");
            for (JsonNode scopeSpanNode : resourceSpanNode
                    .get("scopeSpans")) {
                JsonNode scopeNode = scopeSpanNode.get("scope");
                for (JsonNode spanNode : scopeSpanNode.get("spans")) {
                    spans.add(new JsonNodeSpanWrapper(resourceNode, scopeNode,
                            spanNode));
                }
            }
        }
        exporter.export(spans);
    }

    public String getId() {
        return id;
    }

    public String getConfigProperty(String key) {
        return config.getString(key);
    }
}
