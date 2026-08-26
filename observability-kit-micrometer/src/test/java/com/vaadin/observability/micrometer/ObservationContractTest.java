/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.dom.ElementFactory;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.RpcInvocationEndedEvent;
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;
import com.vaadin.flow.server.data.DataCountEndedEvent;
import com.vaadin.flow.server.data.DataCountStartedEvent;
import com.vaadin.flow.server.data.DataFetchEndedEvent;
import com.vaadin.flow.server.data.DataFetchStartedEvent;
import com.vaadin.observability.micrometer.trace.TracingExecutor;

/**
 * Pins the public span surface: every Observation the kit can emit, with its
 * span name and its low- and high-cardinality attribute keys, compared against
 * the golden file {@code src/test/resources/observation-contract.txt}.
 * <p>
 * Spans are consumed by dashboards and trace queries outside this repository,
 * so a change to this surface is a breaking change for users even though no
 * Java API changed. This test makes such a change visible in review: it fails
 * until the golden file is updated, and the golden-file diff *is* the contract
 * change.
 * <p>
 * Complements {@link MeterTagParityTest}, which pins the Timer tag surface and
 * its equality across the two recording paths. Here each binder is driven
 * through the Observation path only, with fixtures chosen so every optional
 * attribute (component class, RPC event name, data rows) is present — the file
 * records the maximal surface.
 */
class ObservationContractTest {

    private static final String GOLDEN_RESOURCE = "/observation-contract.txt";

    /** One emitted observation: its identity and attribute keys. */
    private record Snapshot(String name, String spanName,
            Set<String> lowCardinalityKeys, Set<String> highCardinalityKeys) {
    }

    private static final class RecordingHandler
            implements ObservationHandler<Observation.Context> {

        final List<Snapshot> snapshots = new ArrayList<>();

        @Override
        public void onStop(Observation.Context ctx) {
            snapshots.add(new Snapshot(ctx.getName(), ctx.getContextualName(),
                    keys(ctx.getLowCardinalityKeyValues()),
                    keys(ctx.getHighCardinalityKeyValues())));
        }

        private static Set<String> keys(Iterable<KeyValue> keyValues) {
            Set<String> keys = new TreeSet<>();
            for (KeyValue kv : keyValues) {
                keys.add(kv.getKey());
            }
            return keys;
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }

    private final RecordingHandler recorder = new RecordingHandler();
    private final ObservationRegistry observations = ObservationRegistry
            .create();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObservabilitySettings settings = ObservabilitySettings
            .builder().build();

    ObservationContractTest() {
        observations.observationConfig().observationHandler(recorder);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        // Both the RPC and the navigation scenario mark the enclosing request;
        // drop the marker so it cannot leak into another test on this thread.
        RequestInteraction.clear();
    }

    @Test
    void observationSurfaceMatchesGoldenFile() {
        driveRequest();
        driveNavigation();
        driveRpc();
        driveDataCount();
        driveDataFetch();
        driveUiAccess();

        String actual = render(recorder.snapshots);
        Assertions.assertEquals(golden(), actual,
                """
                        The span surface changed. This is a breaking change for \
                        dashboards and trace queries built on the previous surface. \
                        If the change is intended, update \
                        observability-kit-micrometer/src/test/resources/observation-contract.txt \
                        to the actual value below so the diff is part of the review:

                        %s"""
                        .formatted(actual));
    }

    /**
     * Fails when a class starts emitting Observations without being driven
     * here: every source file calling {@code Observation.createNotStarted} must
     * have a scenario in this test, or the golden file silently under-reports
     * the surface.
     */
    @Test
    void everyObservationEmitterHasAScenario() throws IOException {
        Set<String> expected = Set.of("RequestMetricsBinder.java",
                "NavigationMetricsBinder.java", "RpcMetricsBinder.java",
                "DataQueryMetricsBinder.java", "TracingExecutor.java");

        Set<String> actual;
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            actual = sources.filter(p -> p.toString().endsWith(".java"))
                    .filter(ObservationContractTest::createsObservations)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        Assertions.assertEquals(new TreeSet<>(expected), actual,
                "the set of observation-emitting classes changed: add a "
                        + "scenario for the new emitter to "
                        + "observationSurfaceMatchesGoldenFile, update the "
                        + "golden file, and extend this list");
    }

    private static boolean createsObservations(Path source) {
        try {
            return Files.readString(source).contains("createNotStarted");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- scenarios ----------

    /**
     * A UIDL POST with no poll/navigation marker, which resolves to the
     * {@code rpc} interaction. The Referer yields the client location.
     */
    private void driveRequest() {
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                observations, settings);

        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getParameter("v-r")).thenReturn("uidl");
        Mockito.when(request.getParameter("v-uiId")).thenReturn("1");
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(request.getHeader("Referer"))
                .thenReturn("http://localhost/contract");
        VaadinResponse response = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(request, response);
        binder.requestEnd(request, response, session);
    }

    @com.vaadin.flow.component.Tag("contract-view")
    private static final class ContractView extends Component {
    }

    private void driveNavigation() {
        NavigationMetricsBinder binder = new NavigationMetricsBinder(registry,
                observations, settings, new RouteTagResolver(10));

        UI ui = Mockito.mock(UI.class);
        UI.setCurrent(ui);
        BeforeEnterEvent enter = Mockito.mock(BeforeEnterEvent.class);
        Mockito.when(enter.getUI()).thenReturn(ui);
        // doReturn: the wildcard in Class<? extends Component> makes the
        // type-safe when(...).thenReturn(...) form uncompilable here.
        Mockito.doReturn(ContractView.class).when(enter).getNavigationTarget();

        binder.beforeEnter(enter);
        binder.afterNavigation(Mockito.mock(AfterNavigationEvent.class));
    }

    /**
     * An RPC invocation carrying an event name and targeting an attached
     * element, so both high-cardinality attributes are present.
     */
    private void driveRpc() {
        RpcMetricsBinder binder = new RpcMetricsBinder(registry, observations,
                settings);

        UI ui = new UI();
        Element element = ElementFactory.createDiv();
        // Constructing the component binds it to the element, which is what
        // lets the binder resolve vaadin.rpc.component from the node id.
        Component ignored = new Component(element) {
        };
        ui.getElement().appendChild(element);

        RpcInvocationStartedEvent started = Mockito
                .mock(RpcInvocationStartedEvent.class);
        Mockito.when(started.getType()).thenReturn("event");
        Mockito.when(started.getName()).thenReturn("click");
        Mockito.when(started.getUI()).thenReturn(ui);
        Mockito.when(started.getNodeId()).thenReturn(element.getNode().getId());
        RpcInvocationEndedEvent ended = Mockito
                .mock(RpcInvocationEndedEvent.class);
        Mockito.when(ended.getType()).thenReturn("event");

        binder.invocationStarted(started);
        binder.invocationEnded(ended);
    }

    private void driveDataCount() {
        DataQueryMetricsBinder binder = new DataQueryMetricsBinder(registry,
                observations, settings);
        UI ui = new UI();
        Component component = new ContractView();

        binder.countStarted(new DataCountStartedEvent(ui, component, true));
        binder.countEnded(new DataCountEndedEvent(ui, component, true, 42));
    }

    private void driveDataFetch() {
        DataQueryMetricsBinder binder = new DataQueryMetricsBinder(registry,
                observations, settings);
        UI ui = new UI();
        Component component = new ContractView();

        binder.fetchStarted(
                new DataFetchStartedEvent(ui, component, 0, 50, true));
        binder.fetchEnded(
                new DataFetchEndedEvent(ui, component, 0, 50, true, 30));
    }

    private void driveUiAccess() {
        TracingExecutor.wrap(Runnable::run, observations).execute(() -> {
        });
    }

    // ---------- golden file ----------

    private static String render(List<Snapshot> snapshots) {
        return snapshots.stream().sorted((a, b) -> a.name().compareTo(b.name()))
                .map(s -> s.name() + "\n" //
                        + "  span-name: " + s.spanName() + "\n" //
                        + "  low:  " + keyList(s.lowCardinalityKeys()) + "\n" //
                        + "  high: " + keyList(s.highCardinalityKeys()) + "\n")
                .collect(Collectors.joining("\n"));
    }

    /**
     * Empty sets render as a word, never as trailing whitespace an editor or
     * formatter would silently strip from the golden file.
     */
    private static String keyList(Set<String> keys) {
        return keys.isEmpty() ? "(none)" : String.join(", ", keys);
    }

    private static String golden() {
        try (InputStream in = ObservationContractTest.class
                .getResourceAsStream(GOLDEN_RESOURCE)) {
            Assertions.assertNotNull(in,
                    GOLDEN_RESOURCE + " is missing from test resources");
            String content = new String(in.readAllBytes(),
                    StandardCharsets.UTF_8);
            // Strip the explanatory header: comment lines and the blank line
            // separating them from the inventory.
            return content.lines().filter(line -> !line.startsWith("#"))
                    .collect(Collectors.joining("\n")).stripLeading() + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
