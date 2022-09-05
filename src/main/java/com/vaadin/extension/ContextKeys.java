package com.vaadin.extension;

import io.opentelemetry.context.ContextKey;

public class ContextKeys {
    public static final ContextKey<String> SESSION_ID = ContextKey
            .named(Constants.SESSION_ID);
}
