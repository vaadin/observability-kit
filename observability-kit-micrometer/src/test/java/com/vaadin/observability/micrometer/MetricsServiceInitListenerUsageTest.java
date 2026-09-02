/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.Map;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.internal.UsageStatistics;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.pro.licensechecker.LicenseChecker;
import com.vaadin.pro.licensechecker.LicenseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class MetricsServiceInitListenerUsageTest {

    private VaadinService service;
    private ServiceInitEvent event;

    @BeforeEach
    void setUp() {
        UsageStatistics.resetEntries();
        service = mock(VaadinService.class, RETURNS_DEEP_STUBS);
        event = mock(ServiceInitEvent.class);
        when(event.getSource()).thenReturn(service);
    }

    @AfterEach
    void tearDown() {
        ObservabilityKit.reset();
        UsageStatistics.resetEntries();
    }

    @Test
    void registeredInstrumentation_marksKitAndEnabledFeaturesAsUsed() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(true);

        new MetricsServiceInitListener().serviceInit(event);

        Map<String, String> entries = entries();
        assertEquals(ObservabilityLicense.PRODUCT_VERSION,
                entries.get("vaadin-observability-kit"));
        assertEquals(ObservabilityLicense.PRODUCT_VERSION,
                entries.get("vaadin-observability-kit/sessions"));
        assertEquals(ObservabilityLicense.PRODUCT_VERSION,
                entries.get("vaadin-observability-kit/traces"));
        // ui-state is off by default
        assertFalse(entries.containsKey("vaadin-observability-kit/ui-state"));
    }

    @Test
    void disabledFeature_isNotMarkedAsUsed() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().sessions(false).build());
        when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(true);

        new MetricsServiceInitListener().serviceInit(event);

        Map<String, String> entries = entries();
        assertFalse(entries.containsKey("vaadin-observability-kit/sessions"));
        assertEquals(ObservabilityLicense.PRODUCT_VERSION,
                entries.get("vaadin-observability-kit"));
    }

    @Test
    void developmentMode_withoutValidLicense_marksNothing() {
        ObservabilityKit.install(new SimpleMeterRegistry(),
                ObservabilitySettings.builder().build());
        when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(false);

        try (var licenseChecker = mockStatic(LicenseChecker.class)) {
            licenseChecker
                    .when(() -> LicenseChecker.checkLicense(any(), any(), any(),
                            any(), anyInt(), any()))
                    .thenThrow(new LicenseException("no valid license"));

            new MetricsServiceInitListener().serviceInit(event);
        }

        assertEquals(0, kitEntryCount());
    }

    @Test
    void kitNotInstalled_marksNothing() {
        when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(true);

        new MetricsServiceInitListener().serviceInit(event);

        assertEquals(0, kitEntryCount());
    }

    // UsageStatistics always holds a default "java" entry, so counting only
    // the kit's entries keeps these assertions honest.
    private static long kitEntryCount() {
        return UsageStatistics.getEntries()
                .filter(entry -> entry.getName()
                        .startsWith("vaadin-observability-kit"))
                .count();
    }

    private static Map<String, String> entries() {
        return UsageStatistics.getEntries()
                .collect(Collectors.toMap(
                        UsageStatistics.UsageEntry::getName,
                        UsageStatistics.UsageEntry::getVersion));
    }
}
