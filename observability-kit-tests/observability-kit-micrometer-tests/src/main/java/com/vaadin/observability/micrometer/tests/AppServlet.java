/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.tests;

import jakarta.servlet.annotation.WebServlet;

import com.vaadin.flow.server.VaadinServlet;

/**
 * Explicit {@link VaadinServlet} declaration. Required here because the WAR
 * also declares {@link MetricsServlet}, which suppresses Vaadin's automatic
 * servlet registration. The more-specific {@code /metrics} mapping still wins
 * over this catch-all for that path.
 */
@WebServlet(asyncSupported = true, urlPatterns = { "/*" })
public class AppServlet extends VaadinServlet {
}
