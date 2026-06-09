/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.micrometer;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

/**
 * Dumps the shared {@link MicrometerRegistry} as a deterministic, sorted
 * plain-text format that the IT can scrape via HTTP.
 */
@WebServlet(urlPatterns = "/metrics", asyncSupported = false)
public class MetricsServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("text/plain; charset=utf-8");
        try (PrintWriter writer = resp.getWriter()) {
            MicrometerRegistry.INSTANCE.getMeters().stream()
                    .sorted((a, b) -> a.getId().getName()
                            .compareTo(b.getId().getName()))
                    .forEach(meter -> writeMeter(writer, meter));
        }
    }

    private void writeMeter(PrintWriter writer, Meter meter) {
        String id = formatId(meter);
        if (meter instanceof Counter c) {
            writer.printf("%s count=%.0f%n", id, c.count());
        } else if (meter instanceof Gauge g) {
            writer.printf("%s value=%.0f%n", id, g.value());
        } else if (meter instanceof Timer t) {
            writer.printf("%s count=%d total_ms=%.3f%n", id, t.count(),
                    t.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));
        }
    }

    private String formatId(Meter meter) {
        StringBuilder sb = new StringBuilder(meter.getId().getName());
        for (Tag tag : meter.getId().getTagsAsIterable()) {
            sb.append(' ').append(tag.getKey()).append('=')
                    .append(tag.getValue());
        }
        return sb.toString();
    }
}
