/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinRequestInterceptor;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.pro.licensechecker.LicenseChecker;
import com.vaadin.pro.licensechecker.LicenseException;

import static com.vaadin.observability.micrometer.ObservabilityLicense.loadAllProperties;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricsServiceInitListenerLicenseTest {

    private VaadinService service;
    private ServiceInitEvent event;

    @BeforeEach
    void setUp() {
        service = mock(VaadinService.class, RETURNS_DEEP_STUBS);
        event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
    }

    @AfterEach
    void tearDown() {
        ObservabilityKit.reset();
    }

    @Test
    void developmentMode_withoutValidLicense_skipsInstrumentation() {
        when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(false);

        try (var licenseChecker = mockStatic(LicenseChecker.class)) {
            licenseChecker
                    .when(() -> LicenseChecker.checkLicense(any(), any(), any(),
                            any(), anyInt(), any()))
                    .thenThrow(new LicenseException("no valid license"));

            new MetricsServiceInitListener().serviceInit(event);
        }

        verify(service, never()).addSessionInitListener(any());
        verify(service, never()).addUIInitListener(any());
        verify(event, never()).addVaadinRequestInterceptor(any());
    }

    @Test
    void developmentMode_withValidLicense_registersInstrumentation() {
        when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(false);

        // An unstubbed static checkLicense is a no-op, i.e. a valid license
        try (var licenseChecker = mockStatic(LicenseChecker.class)) {
            new MetricsServiceInitListener().serviceInit(event);
        }

        // Two UI init listeners in development mode: the UiMetricsBinder and
        // the dev-tools Copilot panel injector (the latter is skipped in
        // production - see productionMode_registersWithoutCheckingLicense).
        verify(service, times(2)).addUIInitListener(any(UIInitListener.class));
        verify(event).addVaadinRequestInterceptor(
                any(VaadinRequestInterceptor.class));
    }

    @Test
    void productionMode_registersWithoutCheckingLicense() {
        when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(true);

        try (var licenseChecker = mockStatic(LicenseChecker.class)) {
            new MetricsServiceInitListener().serviceInit(event);

            // Production builds are validated at build time, not at runtime
            licenseChecker.verifyNoInteractions();
        }

        verify(service).addUIInitListener(any(UIInitListener.class));
    }

    @Test
    void loadAllProperties_throwsError_whenResourceMissing() {
        assertThrows(ExceptionInInitializerError.class,
                () -> loadAllProperties("non-existent.properties"));
    }
}
