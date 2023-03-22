package com.vaadin.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

public class ObservabilityServiceInitListener
        implements VaadinServiceInitListener {
    private static Logger getLogger() {
        return LoggerFactory.getLogger(ObservabilityServiceInitListener.class);
    }

    public void serviceInit(ServiceInitEvent serviceInitEvent) {
        getLogger().info("Initializing Observability Kit");

        serviceInitEvent.addIndexHtmlRequestListener((response) -> {
            Span span = Span.current();
            SpanContext spanContext = span.getSpanContext();
            String traceParent =
                    "00-" + spanContext.getTraceId() + "-" + spanContext.getSpanId() + "-01";

            response.getDocument().head().prependElement("meta")
                    .attr("name", "traceparent")
                    .attr("content", traceParent);
        });

        serviceInitEvent.getSource().addUIInitListener(event -> {
            UI ui = event.getUI();

            ObservabilityHandler handler = ObservabilityHandler
                    .ensureInstalled(ui);

            ui.getChildren()
                    .filter(child -> (child instanceof ObservabilityClient))
                    .forEach(ui::remove);

            ObservabilityClient client = new ObservabilityClient();
            client.getElement().setProperty("instanceId", handler.getId());
            client.getElement().setProperty("serviceName",
                    handler.getConfigProperty("otel.service.name"));
            client.getElement().setProperty("serviceVersion",
                    handler.getConfigProperty("otel.service.version"));
            ui.add(client);
        });
    }
}
