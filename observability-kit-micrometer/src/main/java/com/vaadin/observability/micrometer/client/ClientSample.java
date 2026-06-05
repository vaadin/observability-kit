/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.client;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * One client-side measurement, deserialized by Flow's JSON codec from the
 * browser-side collector.
 */
public class ClientSample implements Serializable {

    private String name;
    private Map<String, String> tags;
    private double valueMs;
    private long ts;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, String> getTags() {
        return tags == null ? Collections.emptyMap() : tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public double getValueMs() {
        return valueMs;
    }

    public void setValueMs(double valueMs) {
        this.valueMs = valueMs;
    }

    public long getTs() {
        return ts;
    }

    public void setTs(long ts) {
        this.ts = ts;
    }
}
