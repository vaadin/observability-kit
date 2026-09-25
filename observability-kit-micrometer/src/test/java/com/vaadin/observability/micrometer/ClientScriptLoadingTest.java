/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.observability.micrometer.client.MetricsCollectorElement;
import com.vaadin.observability.micrometer.devtools.ObservabilityDevToolsHandler;

/**
 * How the kit's browser scripts reach the page: as {@link JsModule}s in the
 * frontend bundle, like any other Flow dependency, not as script text sent
 * through {@code executeJs}.
 */
class ClientScriptLoadingTest {

    @Test
    void collectorScriptIsBundledWithTheElement() {
        JsModule module = assertBundledModule(MetricsCollectorElement.class,
                "VaadinMetricsClient.js");
        Assertions.assertFalse(module.developmentOnly(),
                "the collector runs in production too");
    }

    @Test
    void devToolsPanelIsADevelopmentOnlyModule() {
        JsModule module = assertBundledModule(
                ObservabilityDevToolsHandler.class,
                "VaadinObservabilityDevTools.js");
        Assertions.assertTrue(module.developmentOnly(),
                "the Copilot panel must stay out of production bundles");
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void attachingTheCollectorSendsNoScriptAndTellsItAboutDetails(
            boolean collectErrorMessages) {
        UI ui = uiWithSession();
        MetricsCollectorElement collector = new MetricsCollectorElement(null,
                ObservabilitySettings.builder().build(), collectErrorMessages);

        ui.add(collector);

        Assertions.assertTrue(
                ui.getInternals().dumpPendingJavaScriptInvocations().isEmpty(),
                "the script must not be sent as executeJs text");
        Assertions.assertEquals(collectErrorMessages,
                collector.getElement().hasAttribute("details"));
    }

    private static JsModule assertBundledModule(Class<?> owner,
            String fileName) {
        JsModule module = owner.getAnnotation(JsModule.class);
        Assertions.assertNotNull(module, owner.getSimpleName()
                + " must declare its script as a @JsModule");
        Assertions.assertEquals("./" + fileName, module.value());
        Assertions.assertNotNull(
                owner.getClassLoader()
                        .getResource("META-INF/frontend/" + fileName),
                "the module must ship in the jar's frontend folder");
        return module;
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
}
