package com.vaadin.observability;

import java.io.IOException;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
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

        ui.addDetachListener(detachEvent ->
                session.removeRequestHandler(newObservabilityHandler));
        return newObservabilityHandler;
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
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(request.getInputStream());

            if (!root.has("resourceSpans")) {
                getLogger().error("Malformed traces message.");
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return true;
            }

            handleTraces(root);

            response.setStatus(HttpServletResponse.SC_OK);
        } catch (IOException e) {
            getLogger().error("Exception when processing traces.");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }

        return true;
    }

    public String getId() {
        return id;
    }

    private void handleTraces(JsonNode root) {
        // Dummy method for the agent to hook onto
    }
}
