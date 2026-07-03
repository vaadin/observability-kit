/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer.insights;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.server.communication.RpcInvocationEvent;
import com.vaadin.flow.server.communication.RpcInvocationListener;

/**
 * Captures every failed client-to-server invocation as an
 * {@link ErrorExemplar}.
 * <p>
 * Hooks Flow's {@link RpcInvocationListener} (the same hook
 * {@code RpcMetricsBinder} uses for RPC spans): {@code invocationFailed}
 * delivers the exact "user action + exception" pair, and the event carries the
 * target state node from which the interacted component is resolved. Works in
 * production mode.
 */
public class ErrorBacktrackCollector implements RpcInvocationListener {

    /**
     * Stack frames from these packages are infrastructure, not application
     * code; the first frame NOT matching a prefix is reported as the likely bug
     * location.
     */
    private static final List<String> FRAMEWORK_PREFIXES = List.of("java.",
            "jdk.", "sun.", "jakarta.", "org.springframework.", "org.apache.",
            "io.micrometer.", "com.vaadin.flow.", "com.vaadin.base.",
            "com.vaadin.observability.micrometer.",
            "com.vaadin.observability.spring.");

    private static final int STACK_TOP_FRAMES = 5;

    private final ErrorExemplarBuffer buffer;

    public ErrorBacktrackCollector(ErrorExemplarBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public void invocationFailed(RpcInvocationEvent event, Throwable error) {
        try {
            buffer.add(exemplar(event, error));
        } catch (RuntimeException e) {
            // Collection is best-effort enrichment; never interfere with the
            // framework's own error handling.
        }
    }

    private ErrorExemplar exemplar(RpcInvocationEvent event, Throwable error) {
        UI ui = event.getUI();
        Throwable rootCause = rootCause(error);
        StackTraceElement[] stack = rootCause.getStackTrace();
        return new ErrorExemplar(Instant.now(), route(ui),
                resolveComponentType(event).orElse(null), event.getName(),
                event.getType(), rootCause.getClass().getName(),
                rootCause.getMessage(),
                firstApplicationFrame(stack).orElse(null),
                Arrays.stream(stack).limit(STACK_TOP_FRAMES)
                        .map(StackTraceElement::toString).toList(),
                sessionId(ui), ui != null ? ui.getUIId() : -1);
    }

    private static String route(UI ui) {
        if (ui == null) {
            return null;
        }
        return ui.getInternals().getActiveViewLocation().getPath();
    }

    private static String sessionId(UI ui) {
        if (ui == null || ui.getSession() == null
                || ui.getSession().getSession() == null) {
            return null;
        }
        return ui.getSession().getSession().getId();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Optional<String> firstApplicationFrame(
            StackTraceElement[] stack) {
        return Arrays.stream(stack)
                .filter(frame -> FRAMEWORK_PREFIXES.stream().noneMatch(
                        prefix -> frame.getClassName().startsWith(prefix)))
                .findFirst().map(StackTraceElement::toString);
    }

    /**
     * Resolves the class name of the component the invocation targets, walking
     * up from the target state node to the nearest enclosing component. Same
     * approach as the kit's {@code RpcMetricsBinder}.
     */
    private static Optional<String> resolveComponentType(
            RpcInvocationEvent event) {
        int nodeId = event.getNodeId();
        UI ui = event.getUI();
        if (nodeId < 0 || ui == null) {
            return Optional.empty();
        }
        try {
            StateTree tree = ui.getInternals().getStateTree();
            StateNode node = tree.getNodeById(nodeId);
            if (node == null) {
                return Optional.empty();
            }
            return ComponentUtil.findParentComponent(Element.get(node))
                    .map(component -> component.getClass().getName());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
