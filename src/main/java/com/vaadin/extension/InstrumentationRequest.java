package com.vaadin.extension;

import java.util.Collections;
import java.util.Map;

public class InstrumentationRequest {
    private String name;
    private Map<String, String> attributes;

    public InstrumentationRequest(String name) {
        this(name, Collections.emptyMap());
    }

    public InstrumentationRequest(String name, Map<String, String> attributes) {
        this.name = name;
        this.attributes = attributes;
    }

    public String getName() {
        return name;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
