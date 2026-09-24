/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.util.List;

/**
 * The view collections currently reported as growing. Unlike the other insight
 * sources this is not a buffer of past events but the live state of the UIs
 * still open, so a view that is closed, or whose collection shrinks, drops out
 * of it.
 */
@FunctionalInterface
public interface RetainedStateGrowth {

    /**
     * @return every view field currently growing, in no particular order
     */
    List<GrowingViewState> snapshot();
}
