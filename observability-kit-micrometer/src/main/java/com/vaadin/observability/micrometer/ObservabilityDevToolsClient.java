/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.internal.StringUtil;

/**
 * Loads the in-browser Vaadin Copilot metrics panel. The panel registers itself
 * with Copilot's plugin API and pulls metric snapshots from the server over the
 * dev-tools websocket (see {@code ObservabilityDevToolsHandler}).
 * <p>
 * Injected once per UI and only in development mode; in production Copilot and
 * the dev-tools connection do not exist, so this is never called.
 */
final class ObservabilityDevToolsClient {

    private static final String INIT_KEY = "vaadinObservabilityDevToolsInitialized";
    private static final String CLIENT_RESOURCE = "META-INF/frontend/VaadinObservabilityDevTools.js";

    private ObservabilityDevToolsClient() {
    }

    static void inject(UI ui) {
        if (ui == null || ComponentUtil.getData(ui, INIT_KEY) != null) {
            return;
        }
        ComponentUtil.setData(ui, INIT_KEY, Boolean.TRUE);
        ClassLoader loader = ObservabilityDevToolsClient.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(CLIENT_RESOURCE)) {
            if (in == null) {
                LoggerFactory.getLogger(ObservabilityDevToolsClient.class).warn(
                        "observability-kit dev-tools resource not found: {}",
                        CLIENT_RESOURCE);
                return;
            }
            String js = StringUtil.removeComments(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    true);
            ui.getPage().executeJs(js);
        } catch (IOException e) {
            LoggerFactory.getLogger(ObservabilityDevToolsClient.class).warn(
                    "Could not load observability-kit dev-tools panel code", e);
        }
    }
}
