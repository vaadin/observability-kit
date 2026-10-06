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
 * <p>
 * At most {@code limit} bytes are buffered. A longer body is not inspected
 * ({@link #isTruncated()}): the buffered prefix is replayed and the rest is
 * streamed from the original request, so Flow still reads it unchanged while
 * the kit never holds more than {@code limit} bytes of it.
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final boolean truncated;
    private ServletInputStream overflowStream;

    CachedBodyHttpServletRequest(HttpServletRequest request, int limit)
            throws IOException {
        super(request);
        // readNBytes grows its buffer as bytes arrive, so a forged
        // Content-Length cannot make it allocate up front.
        byte[] read = request.getInputStream().readNBytes(limit + 1);
        this.truncated = read.length > limit;
        this.body = read;
    }

    /**
     * Whether the body exceeded the buffering limit; the cached body is then
     * only a prefix and must not be inspected.
     *
     * @return {@code true} if the body was longer than the limit
     */
    boolean isTruncated() {
        return truncated;
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
    public ServletInputStream getInputStream() throws IOException {
        if (truncated) {
            // The rest of the body can only be read once from the original
            // stream, so hand out one stream that continues where the
            // buffered prefix ends.
            if (overflowStream == null) {
                overflowStream = new PrefixedInputStream(body,
                        super.getInputStream());
            }
            return overflowStream;
        }
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
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(
                new InputStreamReader(getInputStream(), charset()));
    }

    /**
     * Replays an already-read prefix, then continues with the remainder of the
     * original request stream.
     */
    private static final class PrefixedInputStream extends ServletInputStream {

        private final ByteArrayInputStream prefix;
        private final ServletInputStream rest;

        PrefixedInputStream(byte[] prefix, ServletInputStream rest) {
            this.prefix = new ByteArrayInputStream(prefix);
            this.rest = rest;
        }

        @Override
        public int read() throws IOException {
            int b = prefix.read();
            return b != -1 ? b : rest.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (prefix.available() > 0) {
                return prefix.read(b, off, len);
            }
            return rest.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return prefix.available() == 0 && rest.isFinished();
        }

        @Override
        public boolean isReady() {
            return prefix.available() > 0 || rest.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // synchronous replay only; not used for async reads
        }
    }
}
