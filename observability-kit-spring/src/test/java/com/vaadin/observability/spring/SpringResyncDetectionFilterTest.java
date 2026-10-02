/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring;

import jakarta.servlet.FilterChain;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import com.vaadin.observability.micrometer.ResyncInspector;

class SpringResyncDetectionFilterTest {

    private static final String LAST_CLIENT_IDS_ATTR = ResyncInspector.class
            .getName() + ".lastClientIds";

    private final SpringResyncDetectionFilter filter = new SpringResyncDetectionFilter(
            new SimpleMeterRegistry());

    /** Stands in for Flow: reads the whole body, as UidlRequestHandler does. */
    private final FilterChain readBody = (req, res) -> req.getInputStream()
            .readAllBytes();

    @Test
    void regularUidlBodyIsInspected() throws Exception {
        MockHttpServletRequest request = uidl(message(0));

        filter.doFilter(request, new MockHttpServletResponse(), readBody);

        Assertions.assertEquals(Map.of(0, 0), lastClientIds(request));
    }

    @Test
    void forgedContentLengthDoesNotDriveTheAllocation() throws Exception {
        // Before the cache was bounded, Spring sized its first buffer from
        // this header and allocated ~2 GB for a tiny body.
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/") {
            @Override
            public int getContentLength() {
                return Integer.MAX_VALUE - 8;
            }

            @Override
            public long getContentLengthLong() {
                return Integer.MAX_VALUE - 8;
            }
        };
        prepareUidl(request, message(0));

        filter.doFilter(request, new MockHttpServletResponse(), readBody);

        Assertions.assertEquals(Map.of(0, 0), lastClientIds(request));
    }

    @Test
    void bodyOverTheLimitReachesFlowButIsNotInspected() throws Exception {
        String body = "{\"rpc\":[\""
                + "x".repeat(ResyncInspector.MAX_INSPECTED_BODY_BYTES)
                + "\"],\"syncId\":0,\"clientId\":0}";
        MockHttpServletRequest request = uidl(body);
        byte[][] seenByFlow = new byte[1][];

        filter.doFilter(request, new MockHttpServletResponse(), (req,
                res) -> seenByFlow[0] = req.getInputStream().readAllBytes());

        Assertions.assertEquals(body,
                new String(seenByFlow[0], StandardCharsets.UTF_8));
        Assertions.assertNull(lastClientIds(request));
    }

    private static Object lastClientIds(MockHttpServletRequest request) {
        return request.getSession().getAttribute(LAST_CLIENT_IDS_ATTR);
    }

    private static MockHttpServletRequest uidl(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/");
        prepareUidl(request, body);
        return request;
    }

    private static void prepareUidl(MockHttpServletRequest request,
            String body) {
        request.setQueryString("v-r=uidl&v-uiId=0");
        request.addParameter("v-r", "uidl");
        request.addParameter("v-uiId", "0");
        request.setCharacterEncoding("UTF-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setSession(new MockHttpSession());
    }

    private static String message(int clientId) {
        return "{\"csrfToken\":\"x\",\"rpc\":[],\"syncId\":" + (clientId - 1)
                + ",\"clientId\":" + clientId + "}";
    }
}
