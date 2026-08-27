/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the fallbacks of {@link VaadinTelemetryContext#currentRoute()}, which
 * is read by instrumentation outside the Vaadin runtime and so must always
 * hand back a usable tag value.
 */
class VaadinTelemetryContextTest {

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
    }

    @Test
    void theRouteIsReadBackFromTheCurrentUi() {
        UI ui = new UI();
        UI.setCurrent(ui);

        VaadinTelemetryContext.setCurrentRoute(ui, "OrdersView");

        assertEquals("OrdersView", VaadinTelemetryContext.currentRoute());
    }

    @Test
    void withoutACurrentUiTheRouteIsUnknown() {
        // A fetch running on an executor thread has no UI bound to it.
        assertEquals(MeterNames.ROUTE_UNKNOWN,
                VaadinTelemetryContext.currentRoute());
    }

    @Test
    void beforeAnyNavigationTheRouteIsUnknown() {
        UI.setCurrent(new UI());

        assertEquals(MeterNames.ROUTE_UNKNOWN,
                VaadinTelemetryContext.currentRoute());
    }

    /**
     * The attribute is read back as {@code Object}, so anything that is not a
     * String has to be treated as no route at all rather than reported as one.
     */
    @Test
    void aNonStringRouteAttributeIsUnknown() {
        UI ui = new UI();
        UI.setCurrent(ui);

        ComponentUtil.setData(ui, VaadinTelemetryContext.CURRENT_ROUTE_KEY,
                Integer.valueOf(1));

        assertEquals(MeterNames.ROUTE_UNKNOWN,
                VaadinTelemetryContext.currentRoute());
    }

    @Test
    void settingTheRouteOnANullUiIsANoOp() {
        VaadinTelemetryContext.setCurrentRoute(null, "OrdersView");

        assertEquals(MeterNames.ROUTE_UNKNOWN,
                VaadinTelemetryContext.currentRoute());
    }
}
