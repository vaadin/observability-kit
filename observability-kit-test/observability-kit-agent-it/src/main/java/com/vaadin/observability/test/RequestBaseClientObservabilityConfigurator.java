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

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.observability.ObservabilityClientConfiguration;
import com.vaadin.observability.ObservabilityClientConfigurer;

public class RequestBaseClientObservabilityConfigurator
        implements ObservabilityClientConfigurer {

    static final String OBSERVABILITY_ACTIVE = "o11y-active";
    static final String INSTRUMENTATION_LONG_TASK = "o11y-long-task";
    static final String INSTRUMENTATION_DOCUMENT_LOAD = "o11y-document-load";
    static final String INSTRUMENTATION_CLIENT_ERROR = "o11y-error";
    static final String INSTRUMENTATION_XML_HTTP_REQUEST = "o11y-request";
    static final String INSTRUMENTATION_USER_INTERACTION = "o11y-user-interaction";
    static final String IGNORE_VAADIN_URLS = "o11y-req-ignore-vaadin";
    static final String IGNORE_URL = "o11y-req-ignore";
    static final String IGNORE_URL_REGEX = "o11y-req-ignore-regex";

    @Override
    public void configure(ObservabilityClientConfiguration config) {
        VaadinRequest request = VaadinRequest.getCurrent();
        Map<String, List<String>> queryParams = QueryParameters
                .fromString(request.getParameter("query")).getParameters();
        if (!queryParams.containsKey(OBSERVABILITY_ACTIVE)) {
            // Use default configuration
            return;
        }
        UnaryOperator<String> parameterExtractor = key -> queryParams
                .getOrDefault(key, Collections.emptyList()).stream().findFirst()
                .orElse(null);
        boolean observe = Boolean
                .parseBoolean(parameterExtractor.apply(OBSERVABILITY_ACTIVE));
        config.active(observe);
        if (observe) {
            config.traceLongTask(Boolean.parseBoolean(
                    parameterExtractor.apply(INSTRUMENTATION_LONG_TASK)));
            config.traceDocumentLoad(Boolean.parseBoolean(
                    parameterExtractor.apply(INSTRUMENTATION_DOCUMENT_LOAD)));
            config.traceErrors(Boolean.parseBoolean(
                    parameterExtractor.apply(INSTRUMENTATION_CLIENT_ERROR)));
            config.traceXMLHttpRequests(Boolean.parseBoolean(parameterExtractor
                    .apply(INSTRUMENTATION_XML_HTTP_REQUEST)));
            config.ignoreVaadinUrls(Boolean.parseBoolean(
                    parameterExtractor.apply(IGNORE_VAADIN_URLS)));
            if (queryParams.containsKey(INSTRUMENTATION_USER_INTERACTION)) {
                config.traceUserInteraction(new HashSet<>(
                        queryParams.get(INSTRUMENTATION_USER_INTERACTION)));
            } else {
                config.traceUserInteraction(false);
            }
            if (queryParams.containsKey(IGNORE_URL)) {
                config.ignoreUrls(new HashSet<>(queryParams.get(IGNORE_URL)));
            }
            if (queryParams.containsKey(IGNORE_URL_REGEX)) {
                config.ignoreUrlsByPattern(
                        new HashSet<>(queryParams.get(IGNORE_URL_REGEX)));
            }
        }

    }
}
