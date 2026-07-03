/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

import io.micrometer.core.instrument.MeterRegistry;

import com.vaadin.flow.server.communication.UidlRequestHandler;

/**
 * Portable, framework-agnostic servlet filter that observes UIDL message
 * resends and resynchronization requests (prototype, kit-only, no Flow
 * changes). It depends only on the servlet API and Micrometer, so it is meant
 * for plain (non-Spring) deployments; a Spring application does not need this
 * class and should use {@code SpringResyncDetectionFilter} instead.
 * <p>
 * <b>Registering it in a plain servlet app.</b> There is no auto-configuration
 * outside Spring, so register the filter yourself against every request. It
 * should run early (before anything else consumes the request body). For
 * example, in a {@code ServletContextListener} or
 * {@code ServletContainerInitializer}:
 *
 * <pre>{@code
 * FilterRegistration.Dynamic reg = servletContext.addFilter(
 *         "vaadinResyncDetection", new ResyncDetectionFilter(meterRegistry));
 * // isMatchAfter=false so it precedes already-registered filters
 * reg.addMappingForUrlPatterns(null, false, "/*");
 * }</pre>
 *
 * or the equivalent {@code <filter>}/{@code <filter-mapping>} in
 * {@code web.xml}. The {@code MeterRegistry} is whichever instance the
 * application records into.
 * <p>
 * Flow recovers from lost responses entirely inside {@link UidlRequestHandler}
 * by catching {@code ClientResentPayloadException} (replay the cached response)
 * and {@code ResynchronizationRequiredException} (rebuild the UI state);
 * neither surfaces to any Flow listener SPI the kit uses. This filter
 * reconstructs the same signal by buffering the UIDL body (via
 * {@link CachedBodyHttpServletRequest} so Flow can still read it) and handing
 * it to a {@link ResyncInspector}.
 * <p>
 * Because it has no Spring APIs available it buffers the body eagerly; the
 * Spring variant instead captures the bytes lazily as Flow reads them and only
 * counts requests Flow actually consumed. Instrumentation never fails the
 * request: any error while inspecting is swallowed.
 */
public final class ResyncDetectionFilter implements Filter {

    private final ResyncInspector inspector;

    /**
     * Creates the filter recording into the given registry.
     *
     * @param registry
     *            the meter registry, not {@code null}
     */
    public ResyncDetectionFilter(MeterRegistry registry) {
        this.inspector = new ResyncInspector(registry);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http)
                || !ResyncInspector.isUidl(http)) {
            chain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(
                http);
        try {
            HttpSession session = wrapped.getSession(false);
            Object mutex = session != null ? session : this;
            inspector.inspect(wrapped.getCachedBody(), session,
                    ResyncInspector.uiId(wrapped), mutex);
        } catch (RuntimeException instrumentationFailure) {
            // Never break a request because of observability.
        }
        chain.doFilter(wrapped, response);
    }
}
