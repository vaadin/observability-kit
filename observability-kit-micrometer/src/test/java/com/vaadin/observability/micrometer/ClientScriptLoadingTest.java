/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.ui.Dependency;
import com.vaadin.observability.micrometer.client.MetricsCollectorElement;

/**
 * How the kit's browser scripts reach the page: as files the browser loads, the
 * way any other Flow dependency does, not as script text sent through
 * {@code executeJs}. Script text sent that way cannot be cached, is re-sent for
 * every UI, needs {@code unsafe-eval} under a content security policy and
 * reports the page URL as the location of anything it throws.
 */
class ClientScriptLoadingTest {

    private static final String CONTEXT_PROTOCOL = "context://";

    @Test
    void collectorLoadsItsScriptAsAFile() {
        UI ui = uiWithSession();

        ui.add(new MetricsCollectorElement(null,
                ObservabilitySettings.builder().build(), true));
        // What the UIDL writer does for a newly attached component
        ui.getInternals()
                .addComponentDependencies(MetricsCollectorElement.class);

        assertLoadsFileWithoutExecuteJs(ui, "VaadinMetricsClient.js");
    }

    @Test
    void devToolsPanelLoadsItsScriptAsAFile() {
        UI ui = uiWithSession();

        ObservabilityDevToolsClient.inject(ui);

        assertLoadsFileWithoutExecuteJs(ui, "VaadinObservabilityDevTools.js");
    }

    private static UI uiWithSession() {
        DeploymentConfiguration configuration = Mockito
                .mock(DeploymentConfiguration.class);
        VaadinService service = Mockito.mock(VaadinService.class);
        Mockito.when(service.getDeploymentConfiguration())
                .thenReturn(configuration);
        VaadinSession session = Mockito.mock(VaadinSession.class);
        Mockito.when(session.getService()).thenReturn(service);
        Mockito.when(session.getConfiguration()).thenReturn(configuration);
        UI ui = new UI();
        ui.getInternals().setSession(session);
        return ui;
    }

    private static void assertLoadsFileWithoutExecuteJs(UI ui,
            String fileName) {
        Assertions.assertTrue(
                ui.getInternals().dumpPendingJavaScriptInvocations().isEmpty(),
                "the script must not be sent as executeJs text");

        List<Dependency> scripts = ui.getInternals().getDependencyList()
                .getPendingSendToClient().stream()
                .filter(d -> d.getUrl().endsWith("/" + fileName)).toList();
        Assertions.assertEquals(1, scripts.size(),
                "expected one dependency loading " + fileName);

        String url = scripts.get(0).getUrl();
        Assertions.assertTrue(url.startsWith(CONTEXT_PROTOCOL),
                "a runtime-loaded file, not a bundled one: " + url);
        String served = "META-INF/resources/"
                + url.substring(CONTEXT_PROTOCOL.length());
        Assertions.assertNotNull(
                ClientScriptLoadingTest.class.getClassLoader()
                        .getResource(served),
                "the file the dependency points at must be served: " + served);
    }
}
