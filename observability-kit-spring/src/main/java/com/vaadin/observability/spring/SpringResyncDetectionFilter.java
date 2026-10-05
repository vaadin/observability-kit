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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

import com.vaadin.flow.server.communication.UidlRequestHandler;
import com.vaadin.observability.micrometer.ResyncInspector;

/**
 * Spring-optimized servlet filter that observes UIDL message resends and
 * resynchronization requests (prototype, kit-only, no Flow changes). Shares its
 * detection logic with the portable
 * {@link com.vaadin.observability.micrometer.ResyncDetectionFilter} via
 * {@link ResyncInspector}, but is preferred wherever Spring is on the
 * classpath.
 * <p>
 * Flow recovers from lost responses entirely inside {@link UidlRequestHandler}
 * by catching {@code ClientResentPayloadException} (replay the cached response)
 * and {@code ResynchronizationRequiredException} (rebuild the UI state);
 * neither surfaces to any Flow listener SPI the kit uses.
 * <p>
 * Rather than buffer the body eagerly, this filter wraps the request in a
 * {@link ContentCachingRequestWrapper} and inspects <em>after</em> the chain
 * has run: ordering is irrelevant for a counter, and this way we read exactly
 * the bytes Flow read and only count requests Flow actually consumed. The
 * per-UI read-modify-write is guarded by the session mutex. Instrumentation
 * never fails the request: any error while inspecting is swallowed.
 */
public final class SpringResyncDetectionFilter extends OncePerRequestFilter {

    private final ResyncInspector inspector;

    /**
     * Creates the filter recording into the given registry.
     *
     * @param registry
     *            the meter registry, not {@code null}
     */
    public SpringResyncDetectionFilter(MeterRegistry registry) {
        this.inspector = new ResyncInspector(registry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!ResyncInspector.isUidl(request)) {
            chain.doFilter(request, response);
            return;
        }

        // No practical cache limit: Flow buffers the whole body anyway, and a
        // truncated body could hide the resync/clientId fields, which the UIDL
        // JSON places after the (potentially large) rpc array.
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(
                request, Integer.MAX_VALUE);
        try {
            chain.doFilter(wrapped, response);
        } finally {
            try {
                inspect(wrapped);
            } catch (RuntimeException instrumentationFailure) {
                // Never break a request because of observability.
            }
        }
    }

    private void inspect(ContentCachingRequestWrapper request) {
        byte[] cached = request.getContentAsByteArray();
        if (cached.length == 0) {
            // Flow never read the body; nothing to classify.
            return;
        }
        String body = new String(cached, charset(request));

        HttpSession session = request.getSession(false);
        Object mutex = session != null ? WebUtils.getSessionMutex(session)
                : this;
        inspector.inspect(body, session, ResyncInspector.uiId(request), mutex);
    }

    private static Charset charset(HttpServletRequest request) {
        String enc = request.getCharacterEncoding();
        if (enc != null) {
            try {
                return Charset.forName(enc);
            } catch (RuntimeException unsupported) {
                // fall through to default
            }
        }
        return StandardCharsets.UTF_8;
    }
}
