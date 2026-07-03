/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import com.vaadin.flow.component.UI;

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
        ClientResourceLoader.loadOnce(ui, INIT_KEY, CLIENT_RESOURCE,
                ObservabilityDevToolsClient.class);
    }
}
