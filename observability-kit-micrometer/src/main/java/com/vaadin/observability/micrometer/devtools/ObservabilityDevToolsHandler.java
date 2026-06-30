/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.devtools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;

import com.vaadin.base.devserver.DevToolsInterface;
import com.vaadin.base.devserver.DevToolsMessageHandler;
import com.vaadin.observability.micrometer.ObservabilityKit;

/**
 * Dev-mode bridge between the live Micrometer {@link MeterRegistry} and the
 * Vaadin Copilot metrics panel.
 * <p>
 * Discovered via the Java {@link java.util.ServiceLoader} by Flow's dev-tools
 * server (see {@code META-INF/services}). On request from the panel it
 * snapshots every {@code vaadin.*} meter and sends it to the browser over the
 * shared dev-tools websocket. This is a developer-only convenience view; it has
 * no effect in production where the dev-tools connection does not exist.
 */
public class ObservabilityDevToolsHandler implements DevToolsMessageHandler {

    static final String COMMAND_REFRESH = "observability-kit-refresh";
    static final String COMMAND_METRICS = "observability-kit-metrics";

    /** Only meters under this prefix are exposed to the panel. */
    private static final String METER_PREFIX = "vaadin.";

    @Override
    public void handleConnect(DevToolsInterface devToolsInterface) {
        // Push an initial snapshot; the panel also pulls on demand.
        sendSnapshot(devToolsInterface);
    }

    @Override
    public boolean handleMessage(String command, JsonNode data,
            DevToolsInterface devToolsInterface) {
        if (COMMAND_REFRESH.equals(command)) {
            sendSnapshot(devToolsInterface);
            return true;
        }
        return false;
    }

    private void sendSnapshot(DevToolsInterface devToolsInterface) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("meters", snapshot());
        devToolsInterface.send(COMMAND_METRICS, payload);
    }

    private List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> meters = new ArrayList<>();
        MeterRegistry registry = ObservabilityKit.getActiveMeterRegistry();
        if (registry == null) {
            return meters;
        }
        for (Meter meter : registry.getMeters()) {
            Meter.Id id = meter.getId();
            if (!id.getName().startsWith(METER_PREFIX)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", id.getName());
            entry.put("type", id.getType().name());

            Map<String, String> tags = new LinkedHashMap<>();
            for (Tag tag : id.getTags()) {
                tags.put(tag.getKey(), tag.getValue());
            }
            entry.put("tags", tags);

            // Emit derived, interpretable values per meter type rather than raw
            // statistics. For timers the cumulative mean is the stable, useful
            // figure (TOTAL_TIME is an ever-growing sum and the SimpleMeter
            // registry's MAX decays to 0 between polls).
            if (meter instanceof Timer timer) {
                entry.put("count", timer.count());
                entry.put("mean", timer.mean(TimeUnit.MILLISECONDS));
                entry.put("max", timer.max(TimeUnit.MILLISECONDS));
                entry.put("unit", "ms");
            } else if (meter instanceof Counter counter) {
                entry.put("count", (long) counter.count());
            } else if (meter instanceof FunctionCounter counter) {
                entry.put("count", (long) counter.count());
            } else if (meter instanceof Gauge gauge) {
                entry.put("value", gauge.value());
            } else if (meter instanceof DistributionSummary summary) {
                entry.put("count", summary.count());
                entry.put("mean", summary.mean());
                entry.put("max", summary.max());
                if (id.getBaseUnit() != null) {
                    entry.put("unit", id.getBaseUnit());
                }
            } else {
                // Unknown meter type: fall back to raw measurements.
                List<Map<String, Object>> measurements = new ArrayList<>();
                for (Measurement measurement : meter.measure()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("statistic", measurement.getStatistic().name());
                    m.put("value", measurement.getValue());
                    measurements.add(m);
                }
                entry.put("measurements", measurements);
            }
            meters.add(entry);
        }
        return meters;
    }
}
