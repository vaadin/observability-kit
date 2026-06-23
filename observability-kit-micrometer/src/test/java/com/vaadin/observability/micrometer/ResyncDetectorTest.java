/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.observability.micrometer.ResyncDetector.Kind;
import com.vaadin.observability.micrometer.ResyncDetector.Result;

class ResyncDetectorTest {

    private SimpleMeterRegistry registry;
    private ResyncDetector detector;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        detector = new ResyncDetector(registry);
    }

    private static String message(int clientId, int syncId) {
        return "{\"csrfToken\":\"x\",\"rpc\":[],\"syncId\":" + syncId
                + ",\"clientId\":" + clientId + "}";
    }

    private static String resyncMessage(int clientId, int syncId) {
        return "{\"csrfToken\":\"x\",\"rpc\":[],\"syncId\":" + syncId
                + ",\"clientId\":" + clientId + ",\"resynchronize\":true}";
    }

    private double count(String type) {
        try {
            return registry.get(MeterNames.RESYNC)
                    .tag(MeterNames.TAG_TYPE, type).counter().count();
        } catch (MeterNotFoundException notRecorded) {
            return 0d;
        }
    }

    @Test
    void inOrderMessages_areNormal_andAdvanceClientId() {
        int last = ResyncDetector.NO_CLIENT_ID;
        for (int id = 0; id < 5; id++) {
            Result r = detector.inspect(message(id, id - 1), last);
            Assertions.assertEquals(Kind.NORMAL, r.kind());
            Assertions.assertEquals(id, r.lastClientId());
            last = r.lastClientId();
        }
        Assertions.assertEquals(0d, count(MeterNames.RESYNC_TYPE_RESEND));
        Assertions.assertEquals(0d, count(MeterNames.RESYNC_TYPE_RESYNC));
    }

    @Test
    void resentDuplicate_isDetected_andDoesNotAdvanceClientId() {
        // client sent and the server processed message 0 and 1
        Result first = detector.inspect(message(0, -1),
                ResyncDetector.NO_CLIENT_ID);
        Result second = detector.inspect(message(1, 0), first.lastClientId());
        Assertions.assertEquals(Kind.NORMAL, second.kind());

        // response to message 1 was lost; client re-sends message 1 verbatim
        Result resend = detector.inspect(message(1, 0), second.lastClientId());
        Assertions.assertEquals(Kind.RESEND, resend.kind());
        Assertions.assertEquals(1, resend.lastClientId(),
                "a resend must not advance the remembered clientId");

        // the next genuine message is normal again
        Result next = detector.inspect(message(2, 1), resend.lastClientId());
        Assertions.assertEquals(Kind.NORMAL, next.kind());

        Assertions.assertEquals(1d, count(MeterNames.RESYNC_TYPE_RESEND));
        Assertions.assertEquals(0d, count(MeterNames.RESYNC_TYPE_RESYNC));
    }

    @Test
    void resynchronizeFlag_isDetected_evenWithAdvancingClientId() {
        Result first = detector.inspect(message(0, -1),
                ResyncDetector.NO_CLIENT_ID);
        // resync request carries the next, advancing clientId plus the flag
        Result resync = detector.inspect(resyncMessage(1, 0),
                first.lastClientId());
        Assertions.assertEquals(Kind.RESYNC, resync.kind());
        Assertions.assertEquals(1, resync.lastClientId());

        Assertions.assertEquals(1d, count(MeterNames.RESYNC_TYPE_RESYNC));
        Assertions.assertEquals(0d, count(MeterNames.RESYNC_TYPE_RESEND));
    }

    @Test
    void emptyOrMalformedBody_isNormal_andDoesNotThrow() {
        Assertions.assertEquals(Kind.NORMAL, detector.inspect(null, 3).kind());
        Assertions.assertEquals(Kind.NORMAL, detector.inspect("", 3).kind());
        Assertions.assertEquals(Kind.NORMAL,
                detector.inspect("not json", 3).kind());
        // remembered id is preserved across an unparseable body
        Assertions.assertEquals(3,
                detector.inspect("not json", 3).lastClientId());
    }
}
