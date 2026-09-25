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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.vaadin.observability.micrometer.insights.InsightsService;

/**
 * Dev-mode bridge between the live Micrometer {@link MeterRegistry} and the
 * Vaadin Copilot metrics panel.
 * <p>
 * Discovered via the Java {@link java.util.ServiceLoader} by Flow's dev-tools
 * server (see {@code META-INF/services}). It answers two requests from the
 * browser over the shared dev-tools websocket: a meter snapshot of every
 * {@code vaadin.*} meter, and the insights payload
 * {@code /actuator/vaadin/observability} publishes. This is a developer-only
 * convenience view; it has no effect in production where the dev-tools
 * connection does not exist.
 * <p>
 * The two are separate commands because they are wanted at different times. The
 * meter table is only worth polling while the panel is open, whereas the panel
 * watches for new insights whether or not anyone is looking at it, and that
 * background poll should not snapshot the whole registry every time.
 */
public class ObservabilityDevToolsHandler implements DevToolsMessageHandler {

    static final String COMMAND_REFRESH = "observability-kit-refresh";
    static final String COMMAND_METRICS = "observability-kit-metrics";

    static final String COMMAND_INSIGHTS = "observability-kit-insights";
    static final String COMMAND_INSIGHTS_DATA = "observability-kit-insights-data";

    /** Only meters under this prefix are exposed to the panel. */
    private static final String METER_PREFIX = "vaadin.";

    /**
     * Readings kept across polls to give timers and summaries a mean over the
     * window their max covers. One per handler, and there is one handler per
     * dev-tools server.
     */
    private final RecentMeans recentMeans = new RecentMeans();

    @Override
    public void handleConnect(DevToolsInterface devToolsInterface) {
        // Push an initial pair; the panel also pulls on demand.
        sendSnapshot(devToolsInterface);
        sendInsights(devToolsInterface);
    }

    @Override
    public boolean handleMessage(String command, JsonNode data,
            DevToolsInterface devToolsInterface) {
        if (COMMAND_REFRESH.equals(command)) {
            sendSnapshot(devToolsInterface);
            return true;
        }
        if (COMMAND_INSIGHTS.equals(command)) {
            sendInsights(devToolsInterface);
            return true;
        }
        return false;
    }

    private void sendSnapshot(DevToolsInterface devToolsInterface) {
        long now = System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", now);
        payload.put("recentWindowSeconds", RecentMeans.WINDOW.toSeconds());
        payload.put("meters", snapshot(now));
        devToolsInterface.send(COMMAND_METRICS, payload);
    }

    /**
     * Sends the payload {@code /actuator/vaadin/observability} publishes,
     * unaltered: same service, same buffers, same grouping and the same
     * withholding of detail — so a finding read in the panel and one served to
     * an agent cannot drift apart, and {@code schemaVersion} means the same
     * thing in both places.
     * <p>
     * The buffers are looked up per request rather than held, for the reason
     * the registry is: they are bound at {@code serviceInit}, long after the
     * dev-tools server loads this handler. A {@code null} one is not an error
     * here — {@link InsightsService} renders "nothing was watching" as
     * {@code instrumentation: inactive}, which the panel says out loud instead
     * of showing an empty list that reads like "nothing is wrong".
     */
    private void sendInsights(DevToolsInterface devToolsInterface) {
        devToolsInterface.send(COMMAND_INSIGHTS_DATA,
                new InsightsService(ObservabilityKit.getRecentInteractions(),
                        ObservabilityKit.getRecentQueries(),
                        ObservabilityKit.getRecentClientErrors()).payload());
    }

    private List<Map<String, Object>> snapshot(long now) {
        List<Map<String, Object>> meters = new ArrayList<>();
        MeterRegistry registry = ObservabilityKit.getActiveMeterRegistry();
        if (registry == null) {
            return meters;
        }
        Set<Meter.Id> live = new HashSet<>();
        for (Meter meter : registry.getMeters()) {
            Meter.Id id = meter.getId();
            if (!id.getName().startsWith(METER_PREFIX)) {
                continue;
            }
            live.add(id);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", id.getName());
            entry.put("type", id.getType().name());

            Map<String, String> tags = new LinkedHashMap<>();
            for (Tag tag : id.getTags()) {
                tags.put(tag.getKey(), tag.getValue());
            }
            entry.put("tags", tags);

            // Emit derived, interpretable values per meter type rather than raw
            // statistics. For timers and summaries the max decays over the
            // registry's distributionStatisticExpiry while mean() is
            // cumulative, so the two can disagree to the point of mean > max.
            // recentMean is taken over the same window as the max; mean stays
            // the cumulative figure for when the window saw no samples.
            if (meter instanceof Timer timer) {
                entry.put("count", timer.count());
                entry.put("mean", timer.mean(TimeUnit.MILLISECONDS));
                putRecentMean(entry, id, timer.count(),
                        timer.totalTime(TimeUnit.MILLISECONDS), now);
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
                putRecentMean(entry, id, summary.count(), summary.totalAmount(),
                        now);
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
        recentMeans.retainOnly(live);
        return meters;
    }

    /**
     * Adds {@code recentMean} when the last {@link RecentMeans#WINDOW} saw any
     * samples. Left out rather than sent as zero otherwise: a zero would read
     * as "instant", where the truth is "nothing happened".
     */
    private void putRecentMean(Map<String, Object> entry, Meter.Id id,
            long count, double total, long now) {
        double recentMean = recentMeans.record(id, count, total, now);
        if (!Double.isNaN(recentMean)) {
            entry.put("recentMean", recentMean);
        }
    }
}
