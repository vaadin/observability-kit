/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.Objects;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.observability.micrometer.trace.TracingExecutor;

/**
 * Wires Observability Kit instrumentation into a {@code VaadinService} at
 * initialization.
 * <p>
 * Three construction paths:
 * <ul>
 * <li>Spring/Boot — instantiated as a bean with explicit {@code meterRegistry},
 * {@code observationRegistry} (optional), and {@code settings} arguments.</li>
 * <li>Spring/Boot without observation registry — use the two-arg
 * constructor.</li>
 * <li>Standalone — the no-arg constructor is invoked by the Java
 * {@link java.util.ServiceLoader}; the registry and configuration are looked up
 * from {@link ObservabilityKit} at {@code serviceInit} time. If
 * {@link ObservabilityKit#install} was never called, the listener silently
 * no-ops.</li>
 * </ul>
 * <p>
 * When an {@link ObservationRegistry} is available and
 * {@link ObservabilitySettings#isTraces()} is on, the listener also wraps the
 * service's executor with a {@link TracingExecutor} so trace context flows
 * across {@code UI.access(...)} boundaries.
 */
public class MetricsServiceInitListener implements VaadinServiceInitListener {

    private static final Logger LOGGER = LoggerFactory
            .getLogger(MetricsServiceInitListener.class);

    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;
    private final ObservabilitySettings settings;

    /**
     * Constructor used by {@link java.util.ServiceLoader}. Resolves the
     * registry, observation registry, and settings lazily from
     * {@link ObservabilityKit}.
     */
    public MetricsServiceInitListener() {
        this.meterRegistry = null;
        this.observationRegistry = null;
        this.settings = null;
    }

    /**
     * Constructor used by DI containers that don't provide an
     * {@link ObservationRegistry}.
     *
     * @param meterRegistry
     *            Micrometer meter registry, not {@code null}
     * @param settings
     *            instrumentation settings, not {@code null}
     */
    public MetricsServiceInitListener(MeterRegistry meterRegistry,
            ObservabilitySettings settings) {
        this(meterRegistry, null, settings);
    }

    /**
     * Constructor used by DI containers.
     *
     * @param meterRegistry
     *            Micrometer meter registry, not {@code null}
     * @param observationRegistry
     *            Micrometer observation registry, may be {@code null} to
     *            disable Observation-based instrumentation
     * @param settings
     *            instrumentation settings, not {@code null}
     */
    public MetricsServiceInitListener(MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry,
                "meterRegistry");
        this.observationRegistry = observationRegistry;
        this.settings = Objects.requireNonNull(settings, "settings");
        if (observationRegistry != null && settings.isTraces()) {
            installDefaultObservationHandlers(observationRegistry,
                    meterRegistry);
        }
    }

    /**
     * Registers default {@link io.micrometer.observation.ObservationHandler}s
     * that make Observations produce
     * {@link io.micrometer.core.instrument.Timer}s.
     * <p>
     * The default implementation installs
     * {@link DefaultMeterObservationHandler}. Spring Boot deployments override
     * this method to no-op because the Boot Actuator's
     * {@code ObservationAutoConfiguration} already registers the same handler.
     *
     * @param observationRegistry
     *            the registry to configure
     * @param meterRegistry
     *            the meter registry to attach timers to
     */
    protected void installDefaultObservationHandlers(
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {
        observationRegistry.observationConfig().observationHandler(
                new DefaultMeterObservationHandler(meterRegistry));
    }

    /**
     * Hook for DI integrations to enrich the framework-level HTTP observation
     * (e.g. Spring's {@code ServerHttpObservationFilter} span) with
     * Vaadin-specific information so the parent HTTP span renders informatively
     * in the trace UI. Called from {@link RequestMetricsBinder} after the
     * Vaadin request type has been determined and before the
     * {@code vaadin.request.<type>} child observation is started.
     * <p>
     * Default implementation no-ops, keeping the framework-agnostic core free
     * of Spring imports. The Spring/Boot integration modules override this to
     * call into their respective HTTP-observation APIs.
     *
     * @param request
     *            the current Vaadin request
     * @param requestType
     *            the classified request type (e.g. {@code uidl},
     *            {@code heartbeat}, {@code push}, {@code static},
     *            {@code other})
     */
    protected void enrichHttpObservation(VaadinRequest request,
            String requestType) {
        // no-op by default
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        MeterRegistry r = meterRegistry != null ? meterRegistry
                : ObservabilityKit.getMeterRegistry();
        ObservabilitySettings s = settings != null ? settings
                : ObservabilityKit.getSettings();
        if (r == null || s == null) {
            return;
        }
        boolean productionMode = event.getSource().getDeploymentConfiguration()
                .isProductionMode();
        if (!ObservabilityLicense.isLicensed(productionMode)) {
            LOGGER.warn(
                    "No valid {} license found. Observability Kit instrumentation "
                            + "will not be registered and no telemetry will be collected. "
                            + "See https://vaadin.com/commercial-license-and-service-terms",
                    ObservabilityLicense.PRODUCT_NAME);
            return;
        }
        ObservationRegistry or = observationRegistry != null
                ? observationRegistry
                : ObservabilityKit.getObservationRegistry();
        // Record the bound registry so the dev-mode Copilot metrics panel can
        // read the live meters regardless of deployment type.
        ObservabilityKit.setActiveMeterRegistry(r);
        bind(event, r, or, s);
        if (!productionMode) {
            event.getSource()
                    .addUIInitListener(uiEvent -> ObservabilityDevToolsClient
                            .inject(uiEvent.getUI()));
        }
    }

    void bind(ServiceInitEvent event, MeterRegistry registry,
            ObservationRegistry observationRegistry,
            ObservabilitySettings settings) {
        VaadinService service = event.getSource();

        if (settings.isSessions()) {
            SessionMetricsBinder binder = new SessionMetricsBinder(registry);
            service.addSessionInitListener(binder);
            service.addSessionDestroyListener(binder);
            service.addSessionLockListener(
                    new SessionLockMetricsBinder(registry));
        }

        if (settings.isUis() || settings.isNavigation()
                || settings.isClient()) {
            service.addUIInitListener(new UiMetricsBinder(registry,
                    observationRegistry, settings));
        }

        if (settings.isRequests() || settings.isErrors()) {
            event.addVaadinRequestInterceptor(
                    new RequestMetricsBinder(registry, observationRegistry,
                            settings, this::enrichHttpObservation));
        }

        if (settings.isRequests()) {
            service.addRpcInvocationListener(new RpcMetricsBinder(registry,
                    observationRegistry, settings));
        }

        if (settings.isTraces() && observationRegistry != null) {
            event.getExecutor().ifPresent(exec -> event.setExecutor(
                    new TracingExecutor(exec, observationRegistry)));
        }
    }
}
