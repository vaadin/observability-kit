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
import java.io.ObjectInputStream;
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

/**
 * This class handles Observability messages, which consist of JSON
 * representations of Frontend Observability traces.
 */
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

    transient Function<String, String> config = (key) -> {
        getLogger().error("Observability agent is not running");
        return null;
    };
    transient BiConsumer<String, Map<String,Object>> exporter = (id, map) -> {
        getLogger().error("Observability agent is not running");
    };

    /**
     * Installs an Observability handler onto the provided UI.
     *
     * @param ui the UI that the handler should be installed on
     * @return the installed ObservabilityHandler
     */
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

    /**
     * Returns whether the provided request conforms to a Frontend
     * Observability message and that the associated ID matches the handler ID.
     *
     * @param request the Vaadin request
     * @return true if the request can be handled
     */
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

    /**
     * Handles a Frontend Observability message within a request. The
     * incoming JSON string is mapped to a hierarchical map of native Java
     * objects and sent to a callback injected by the Observability agent.
     *
     * @param session the Vaadin session
     * @param request the Vaadin request
     * @param response the Vaadin response
     * @return true if the request has been handled and should not be
     * processed further.
     */
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

    /**
     * Returns the unique ID for the installed handler.
     *
     * @return the unique handler ID
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the string value of the provided configuration key name from a
     * callback injected by the Observability agent.
     *
     * @param key
     *            the configuration key name
     * @return the string value of the key
     */
    public String getConfigProperty(String key) {
        return config.apply(key);
    }

    /**
     * Override default deserialization logic to account for transient fields.
     *
     * @param stream
     *            the object to read
     * @throws IOException
     *            if an IO error occured
     * @throws ClassNotFoundException
     *            if the class of the stream object could not be found
     */
    public void readObject(ObjectInputStream stream)
            throws IOException, ClassNotFoundException {
        stream.defaultReadObject();
        config = (key) -> {
            getLogger().error("");
            return null;
        };
        exporter = (id, map) -> {
            getLogger().error("");
        };
    }
}
