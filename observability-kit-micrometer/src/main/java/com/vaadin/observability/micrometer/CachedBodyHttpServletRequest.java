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
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Wraps a request and buffers its body so it can be read more than once: the
 * kit inspects it for resend/resync detection and Flow still reads the same
 * bytes downstream. Used by {@link ResyncDetectionFilter}.
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyHttpServletRequest(HttpServletRequest request)
            throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    String getCachedBody() {
        return new String(body, charset());
    }

    private Charset charset() {
        String enc = getCharacterEncoding();
        if (enc != null) {
            try {
                return Charset.forName(enc);
            } catch (RuntimeException unsupported) {
                // fall through to default
            }
        }
        return StandardCharsets.UTF_8;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return buffer.read();
            }

            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // synchronous replay only; not used for async reads
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(
                new InputStreamReader(getInputStream(), charset()));
    }
}
