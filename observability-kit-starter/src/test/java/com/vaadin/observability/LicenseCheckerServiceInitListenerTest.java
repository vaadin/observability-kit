/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.pro.licensechecker.BuildType;
import com.vaadin.pro.licensechecker.LicenseChecker;

import static com.vaadin.observability.LicenseCheckerServiceInitListener.loadAllProperties;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.text.MatchesPattern.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LicenseCheckerServiceInitListenerTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private VaadinService service;

    private MockedStatic<LicenseChecker> licenseChecker;

    @BeforeEach
    public void setup() {
        licenseChecker = mockStatic(LicenseChecker.class);
    }

    @AfterEach
    public void cleanup() {
        licenseChecker.close();
    }

    @Test
    public void developmentMode_licenseIsCheckedRuntime() {
        when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(false);

        final var properties = loadAllProperties(LicenseCheckerServiceInitListener.PROPERTIES_RESOURCE);

        final var version = properties.getProperty(
                LicenseCheckerServiceInitListener.VERSION_PROPERTY);

        // Assert version is in X.Y format
        assertThat(version, matchesPattern("^\\d\\.\\d.*"));

        final var listener = new LicenseCheckerServiceInitListener();
        listener.serviceInit(new ServiceInitEvent(service));

        // Verify the license is checked
        BuildType buildType = null;
        licenseChecker.verify(() -> LicenseChecker.checkLicense(
                LicenseCheckerServiceInitListener.PRODUCT_NAME, version,
                buildType));
    }

    @Test
    public void productionMode_licenseIsNotCheckedRuntime() {
        when(service.getDeploymentConfiguration().isProductionMode())
                .thenReturn(true);

        final var listener = new LicenseCheckerServiceInitListener();
        listener.serviceInit(new ServiceInitEvent(service));

        licenseChecker.verifyNoInteractions();
    }

    @Test
    public void serviceInit_throwsError_whenPropertiesLoadFails() {
        assertThrows(ExceptionInInitializerError.class, () -> {
            LicenseCheckerServiceInitListener.loadAllProperties("non-existent");
        });
    }
}
