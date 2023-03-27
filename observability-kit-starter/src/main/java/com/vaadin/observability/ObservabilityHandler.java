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

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.SynchronizedRequestHandler;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.ApplicationConstants;

public class ObservabilityHandler extends SynchronizedRequestHandler {
    private static Logger getLogger() {
        return LoggerFactory.getLogger(ObservabilityHandler.class);
    }

    private static final String PATH = "/";
    private static final String ID_PARAMETER = "id";
    private static final String REQUEST_TYPE = "o11y";
    private static final String HTTP_METHOD = "POST";
    private static final String CONTENT_TYPE = "application/json";

    private final String id = UUID.randomUUID().toString();

    public Function<String, String> config = (key) -> null;
    public BiConsumer<String, Map<String,Object>> exporter = (id, map) -> {};

    static ObservabilityHandler ensureInstalled(UI ui) {
        ObservabilityHandler observabilityHandler = ComponentUtil.getData(ui,
                ObservabilityHandler.class);
        if (observabilityHandler != null) {
            // Already installed, return the existing handler
            return observabilityHandler;
        }

        ObservabilityHandler newObservabilityHandler =
                new ObservabilityHandler();

        VaadinSession session = ui.getSession();
        session.addRequestHandler(newObservabilityHandler);
        ComponentUtil.setData(ui, ObservabilityHandler.class,
                newObservabilityHandler);

        ui.addAttachListener(attachEvent -> {
            UI attachUI = attachEvent.getUI();
            VaadinSession attachSession = attachEvent.getSession();
            if (ComponentUtil.getData(attachUI, ObservabilityHandler.class) == null) {
                attachSession.addRequestHandler(newObservabilityHandler);
                ComponentUtil.setData(attachUI, ObservabilityHandler.class,
                        newObservabilityHandler);
            }
        });
        ui.addDetachListener(detachEvent -> {
            UI detachUI = detachEvent.getUI();
            VaadinSession detachSession = detachEvent.getSession();
            if (ComponentUtil.getData(detachUI, ObservabilityHandler.class) != null) {
                detachSession.removeRequestHandler(newObservabilityHandler);
                ComponentUtil.setData(detachUI, ObservabilityHandler.class, null);
            }
        });
        return newObservabilityHandler;
    }

    public ObservabilityHandler() {
    }

    @Override
    protected boolean canHandleRequest(VaadinRequest request) {
        if (!PATH.equals(request.getPathInfo())) {
            return false;
        }
        String requestType = request
                .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER);
        String instanceId = request.getParameter(ID_PARAMETER);
        return REQUEST_TYPE.equals(requestType) && id.equals(instanceId);
    }

    @Override
    public boolean synchronizedHandleRequest(VaadinSession session,
            VaadinRequest request, VaadinResponse response) {
        if (!HTTP_METHOD.equals(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return true;
        }
        if (!CONTENT_TYPE.equals(request.getContentType())) {
            response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
            return true;
        }

        try {
            Map<String,Object> objectMap =
                    new ObjectMapper().readerForMapOf(Object.class)
                            .readValue(request.getInputStream());
            if (!objectMap.containsKey("resourceSpans")) {
                getLogger().error("Malformed traces message.");
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return true;
            }
            exporter.accept(id, objectMap);
            response.setStatus(HttpServletResponse.SC_OK);
        } catch (IOException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }

        return true;
    }

    public String getId() {
        return id;
    }

    public String getConfigProperty(String key) {
        return config.apply(key);
    }
}
