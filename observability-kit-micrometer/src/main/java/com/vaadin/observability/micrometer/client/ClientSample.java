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
    private Map<String, String> detail;
    private double valueMs;
    private long ts;
    private long ageMs;

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

    /**
     * Free-form context that describes the sample but must never become a meter
     * tag: the message, source and first stack frame of a browser error, and
     * the path it happened on. It is what a counter cannot carry — a number
     * says an error happened, not what it was — so the values here feed the
     * insight the sample produces, never its tags.
     *
     * @return the detail map, empty when the browser sent none
     */
    public Map<String, String> getDetail() {
        return detail == null ? Collections.emptyMap() : detail;
    }

    public void setDetail(Map<String, String> detail) {
        this.detail = detail;
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

    /**
     * How long this sample sat in the browser's buffer <em>while the browser
     * could not reach the server</em>, measured at flush time.
     * <p>
     * Offline time, not elapsed time. The collector flushes on an interval, so
     * every sample waits a little; what makes a sample interesting is having
     * waited because the server was unreachable, and only that is counted here.
     * <p>
     * A non-zero value says <em>this sample could not be sent when it was
     * taken</em>. It does not say the thing it describes happened during an
     * outage: a sample taken while the browser was connected still accrues the
     * outage that begins before the next flush. The distinction matters for a
     * browser error, where it is tempting to read the number as "this error
     * happened while the user was offline".
     * <p>
     * Computed in the browser on purpose, and on a monotonic clock rather than
     * the one that produced {@link #getTs()}. A sample taken while the
     * connection was down can only arrive once it is back, so subtracting
     * {@code ts} from the arrival time would report the skew between two
     * machines' clocks rather than a delay — and a wall clock that steps
     * mid-outage, which is exactly when this is being measured, would corrupt
     * the figure even on one machine.
     *
     * @return the offline wait in milliseconds, {@code 0} for a sample that
     *         only waited for the next flush
     */
    public long getAgeMs() {
        return ageMs;
    }

    public void setAgeMs(long ageMs) {
        this.ageMs = ageMs;
    }
}
