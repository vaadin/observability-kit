/*
 * Copyright (C) 2023 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 *
 */

package com.vaadin.observability.test;

import com.vaadin.testbench.TestBenchElement;
import com.vaadin.testbench.elementsbase.Element;

@Element("vaadin-observability-client")
public class ObservabilityClientElement extends TestBenchElement {

    public String getInstanceId() {
        return getPropertyString("instanceId");
    }
}
