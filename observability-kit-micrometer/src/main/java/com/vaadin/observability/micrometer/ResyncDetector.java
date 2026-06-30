/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

import com.vaadin.flow.internal.JacksonUtils;
import com.vaadin.flow.shared.ApplicationConstants;

/**
 * Prototype detector that classifies an incoming UIDL request body as a normal
 * message, a re-sent (duplicate) message, or a full resynchronization request,
 * and records a {@link MeterNames#RESYNC} counter for the two recovery cases.
 * <p>
 * The classification mirrors Flow's own server-side logic in
 * {@code ServerRpcHandler#handleRpc}, which is otherwise invisible to the kit
 * because the relevant {@code ClientResentPayloadException} and
 * {@code ResynchronizationRequiredException} are caught internally by
 * {@code UidlRequestHandler} and never surface to a {@code
 * VaadinRequestInterceptor}:
 * <ul>
 * <li><b>Resync</b> &mdash; the request carries
 * {@link ApplicationConstants#RESYNCHRONIZE_ID}{@code = true}. This is checked
 * first because a resync request also carries a normally advancing
 * {@code clientId}.</li>
 * <li><b>Resend</b> &mdash; the request's
 * {@link ApplicationConstants#CLIENT_TO_SERVER_ID} is not greater than the last
 * one seen for the same UI, i.e. the client re-sent a message the server had
 * already processed (Flow compares against
 * {@code lastProcessedClientToServerId + 1}).</li>
 * </ul>
 * <p>
 * The detector is stateless: the caller supplies the previously seen
 * {@code clientId} and stores the {@link Result#lastClientId()} returned here
 * (e.g. as an HTTP session attribute keyed by UI id), so there is no unbounded
 * per-session state held inside the kit.
 */
public final class ResyncDetector {

    /** No {@code clientId} has been seen yet for a UI. */
    public static final int NO_CLIENT_ID = -1;

    /** Classification of a single UIDL request. */
    public enum Kind {
        /** An ordinary, in-order message. */
        NORMAL,
        /** A duplicate the client re-sent; the server replays its response. */
        RESEND,
        /** A client-requested full UI-state resynchronization. */
        RESYNC
    }

    /**
     * Result of classifying a request.
     *
     * @param kind
     *            the classification
     * @param lastClientId
     *            the {@code clientId} the caller should remember for this UI
     *            for the next request (unchanged from the input for a resend)
     */
    public record Result(Kind kind, int lastClientId) {
    }

    private final MeterRegistry registry;

    /**
     * Creates a detector recording into the given registry.
     *
     * @param registry
     *            the meter registry, not {@code null}
     */
    public ResyncDetector(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Classifies a UIDL request body and records a counter for resend/resync
     * events.
     *
     * @param requestBody
     *            the raw UIDL request body (JSON); may be {@code null} or empty
     * @param previousClientId
     *            the last {@code clientId} seen for this UI, or
     *            {@link #NO_CLIENT_ID} if none
     * @return the classification and the {@code clientId} to remember next
     */
    public Result inspect(String requestBody, int previousClientId) {
        Result result = classify(requestBody, previousClientId);
        switch (result.kind()) {
        case RESEND -> registry.counter(MeterNames.RESYNC, MeterNames.TAG_TYPE,
                MeterNames.RESYNC_TYPE_RESEND).increment();
        case RESYNC -> registry.counter(MeterNames.RESYNC, MeterNames.TAG_TYPE,
                MeterNames.RESYNC_TYPE_RESYNC).increment();
        default -> {
            // NORMAL: nothing to record
        }
        }
        return result;
    }

    private static Result classify(String requestBody, int previousClientId) {
        if (requestBody == null || requestBody.isBlank()) {
            return new Result(Kind.NORMAL, previousClientId);
        }
        JsonNode json;
        try {
            json = JacksonUtils.readTree(requestBody);
        } catch (RuntimeException malformed) {
            // Not our concern to validate the payload; let Flow handle it.
            return new Result(Kind.NORMAL, previousClientId);
        }
        if (json == null) {
            return new Result(Kind.NORMAL, previousClientId);
        }

        int clientId = json.has(ApplicationConstants.CLIENT_TO_SERVER_ID)
                ? json.get(ApplicationConstants.CLIENT_TO_SERVER_ID).intValue()
                : NO_CLIENT_ID;

        // Resync takes precedence: such a request also carries a normally
        // advancing clientId, so it must be classified before the comparison.
        if (json.has(ApplicationConstants.RESYNCHRONIZE_ID) && json
                .get(ApplicationConstants.RESYNCHRONIZE_ID).booleanValue()) {
            int next = clientId >= 0 ? Math.max(previousClientId, clientId)
                    : previousClientId;
            return new Result(Kind.RESYNC, next);
        }

        if (clientId < 0) {
            return new Result(Kind.NORMAL, previousClientId);
        }
        if (previousClientId != NO_CLIENT_ID && clientId <= previousClientId) {
            // The client re-sent a message id the server already advanced past;
            // do not advance the remembered id.
            return new Result(Kind.RESEND, previousClientId);
        }
        return new Result(Kind.NORMAL, clientId);
    }
}
