/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CachedBodyHttpServletRequestTest {

    @Test
    void bodyWithinLimitIsCachedAndReplayable() throws IOException {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(
                requestWithBody("{\"clientId\":3}"), 64);

        Assertions.assertFalse(wrapped.isTruncated());
        Assertions.assertEquals("{\"clientId\":3}", wrapped.getCachedBody());
        Assertions.assertEquals("{\"clientId\":3}",
                new String(wrapped.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8));
    }

    @Test
    void bodyExactlyAtLimitIsNotTruncated() throws IOException {
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(
                requestWithBody("12345678"), 8);

        Assertions.assertFalse(wrapped.isTruncated());
        Assertions.assertEquals("12345678", wrapped.getCachedBody());
    }

    @Test
    void bodyOverLimitIsTruncatedButStillReadInFull() throws IOException {
        String body = "x".repeat(100) + "{\"clientId\":3}";
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(
                requestWithBody(body), 16);

        Assertions.assertTrue(wrapped.isTruncated());
        Assertions.assertEquals(body,
                new String(wrapped.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8));
        Assertions.assertTrue(wrapped.getInputStream().isFinished());
    }

    private static HttpServletRequest requestWithBody(String body)
            throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8));
        ServletInputStream stream = new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // not used
            }
        };
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getInputStream()).thenReturn(stream);
        return request;
    }
}
