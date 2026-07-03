/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.Notification.Position;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.theme.aura.Aura;

/**
 * Demo application for the "Actionable production insights consumable by AI
 * agents" feature: a small orders UI where one specific user interaction fails,
 * and the failure is backtracked to a replicable interaction at
 * {@code GET /actuator/vaadin/observability}.
 */
@SpringBootApplication
@StyleSheet(Aura.STYLESHEET)
public class DemoApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    /**
     * Shows a friendly error notification when an unhandled exception occurs.
     * Deliberately a session {@code ErrorHandler}, not a try/catch in the click
     * listener: the exception must propagate through Flow's RPC handling for
     * Observability Kit to capture it as an error exemplar.
     */
    @Bean
    VaadinServiceInitListener errorNotificationInitListener() {
        return serviceInit -> serviceInit.getSource().addSessionInitListener(
                DemoApplication::configureErrorNotification);
    }

    private static void configureErrorNotification(
            SessionInitEvent sessionInit) {
        sessionInit.getSession().setErrorHandler(errorEvent -> {
            if (UI.getCurrent() == null) {
                return;
            }
            Notification error = new Notification(
                    "Something went wrong. We are on it!");
            error.setPosition(Position.MIDDLE);
            error.addThemeVariants(NotificationVariant.ERROR);
            error.open();
        });
    }
}
