/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.extension;

import io.opentelemetry.context.ContextKey;

public class ContextKeys {
    public static final ContextKey<String> SESSION_ID = ContextKey
            .named(Constants.SESSION_ID);
}
