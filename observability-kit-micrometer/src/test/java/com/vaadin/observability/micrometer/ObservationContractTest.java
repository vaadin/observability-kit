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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
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
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.LocationChangeEvent;
import com.vaadin.flow.router.NavigationTrigger;
import com.vaadin.flow.router.Router;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.RpcInvocationEndedEvent;
import com.vaadin.flow.server.communication.RpcInvocationStartedEvent;
import com.vaadin.flow.server.data.DataCountEndedEvent;
import com.vaadin.flow.server.data.DataCountStartedEvent;
import com.vaadin.flow.server.data.DataFetchEndedEvent;
import com.vaadin.flow.server.data.DataFetchStartedEvent;
import com.vaadin.observability.micrometer.trace.ObservationNames;
import com.vaadin.observability.micrometer.trace.TracingExecutor;

/**
 * Pins the public span surface: every Observation the kit can emit, with the
 * full set of span names it can produce and its low- and high-cardinality
 * attribute keys, compared against the golden file
 * {@code src/test/resources/observation-contract.txt}.
 * <p>
 * Spans are consumed by dashboards and trace queries outside this repository,
 * so a change to this surface is a breaking change for users even though no
 * Java API changed. This test makes such a change visible in review: it fails
 * until the golden file is updated, and the golden-file diff *is* the contract
 * change.
 * <p>
 * Complements {@link MeterTagParityTest}, which pins the Timer tag surface —
 * including the {@code error} tag that {@code DefaultMeterObservationHandler}
 * derives from the Observation's error and which therefore never appears among
 * the attribute keys recorded here. Each binder is driven through the
 * Observation path only, with fixtures chosen so every optional attribute
 * (component class, RPC event name, data rows) is present and every request
 * classification is exercised — the file records the maximal surface.
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
        driveRequests();
        driveNavigation();
        driveRpc();
        driveDataCount();
        driveDataFetch();
        driveUiAccess();

        String actual = render(recorder.snapshots);
        Assertions.assertEquals(golden(), actual.strip(),
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
     * The key-shape guard in {@link #render(List)} must trip on a divergent
     * high-cardinality set even when the low sets agree — a failing data fetch,
     * which legitimately omits {@code vaadin.data.rows}, is exactly that shape
     * and must be reconciled in the scenarios rather than silently absorbed
     * into the golden file.
     */
    @Test
    void keyShapeGuardTripsOnDivergentHighCardinalityKeys() {
        List<Snapshot> divergent = List.of(
                new Snapshot("vaadin.x", "vaadin.x.a", Set.of("outcome"),
                        Set.of("rows")),
                new Snapshot("vaadin.x", "vaadin.x.b", Set.of("outcome"),
                        Set.of()));
        Assertions.assertThrows(AssertionError.class, () -> render(divergent),
                "same low keys with different high keys must not pass");
    }

    /**
     * Fails when a class starts emitting Observations without being driven
     * here: every source file creating an Observation must have a scenario in
     * this test, or the golden file silently under-reports the surface.
     * <p>
     * Detection is a static-call regex over the sources — a deliberate 80%
     * tool. It cannot see an emitter hiding the static call behind another
     * name, and it flags a file whose javadoc merely spells out a creation
     * call; both failure modes are loud rather than silent, which is what the
     * tripwire is for.
     */
    @Test
    void everyObservationEmitterHasAScenario() throws IOException {
        Set<String> expected = Set.of("RequestMetricsBinder.java",
                "NavigationMetricsBinder.java", "RpcMetricsBinder.java",
                "DataQueryMetricsBinder.java", "TracingExecutor.java");

        Assertions.assertEquals(new TreeSet<>(expected),
                observationEmitters(Path.of("src/main/java")),
                "the set of observation-emitting classes changed: add a "
                        + "scenario for the new emitter to "
                        + "observationSurfaceMatchesGoldenFile, update the "
                        + "golden file, and extend this list");
    }

    /**
     * Names of the source files under {@code root} that create Observations
     * through the static {@code Observation.createNotStarted(..)} or
     * {@code Observation.start(..)} entry points (with or without an
     * {@code ObservationConvention}, which goes through the same statics).
     */
    static Set<String> observationEmitters(Path root) throws IOException {
        Assertions.assertTrue(Files.isDirectory(root),
                root + " not found: this test resolves sources against the "
                        + "module directory, so run it with the module as "
                        + "working directory (as Surefire does)");
        Pattern creation = Pattern.compile(
                "Observation\\s*\\.\\s*(createNotStarted|start)\\s*\\(");
        try (Stream<Path> sources = Files.walk(root)) {
            return sources.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> creation.matcher(read(p)).find())
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- scenarios ----------

    /**
     * Drives every request classification, so the golden file pins the full
     * {@code vaadin.request.*} span-name set: each non-UIDL type keeps its
     * type-based name, while a UIDL request is renamed after the interaction a
     * listener marked during handling ({@code rpc} when nothing marked — the
     * {@code none} interaction value never becomes a span name).
     */
    private void driveRequests() {
        // UIDL, no marker: defaults to the rpc interaction.
        driveRequest(uidlRequest(), null);
        // UIDL, marked by the poll listener during handling.
        driveRequest(uidlRequest(), ObservationNames.INTERACTION_POLL);
        // UIDL, marked by the navigation listener during handling.
        driveRequest(uidlRequest(), ObservationNames.INTERACTION_NAVIGATION);
        // The non-UIDL types keep their type-based span name.
        driveRequest(request(r -> Mockito.when(r.getParameter("v-r"))
                .thenReturn("heartbeat")), null);
        driveRequest(request(
                r -> Mockito.when(r.getPathInfo()).thenReturn("/PUSH/xyz")),
                null);
        driveRequest(request(r -> Mockito.when(r.getPathInfo())
                .thenReturn("/VAADIN/build/app.js")), null);
        driveRequest(request(r -> {
        }), null);
    }

    private interface RequestCustomizer {
        void accept(VaadinRequest request);
    }

    private static VaadinRequest request(RequestCustomizer customizer) {
        VaadinRequest request = Mockito.mock(VaadinRequest.class);
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(request.getHeader("Referer"))
                .thenReturn("http://localhost/contract");
        customizer.accept(request);
        return request;
    }

    private static VaadinRequest uidlRequest() {
        return request(r -> {
            Mockito.when(r.getParameter("v-r")).thenReturn("uidl");
            Mockito.when(r.getParameter("v-uiId")).thenReturn("1");
        });
    }

    private void driveRequest(VaadinRequest request, String interactionMark) {
        RequestMetricsBinder binder = new RequestMetricsBinder(registry,
                observations, settings);
        VaadinResponse response = Mockito.mock(VaadinResponse.class);
        VaadinSession session = Mockito.mock(VaadinSession.class);

        binder.requestStart(request, response);
        if (interactionMark != null) {
            // What the poll/navigation listeners do during request handling.
            RequestInteraction.mark(interactionMark);
        }
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
        binder.afterNavigation(new AfterNavigationEvent(new LocationChangeEvent(
                Mockito.mock(Router.class), ui, NavigationTrigger.UI_NAVIGATE,
                new Location("view"), List.of())));
    }

    /**
     * An RPC invocation carrying an event name and targeting an attached
     * element, so both high-cardinality attributes are present. The span name
     * is {@code vaadin.rpc.<type>}, where the type passes through Flow's
     * invocation type verbatim — the set of types is owned by Flow's RPC
     * handlers, so the golden file pins the pattern through one representative,
     * not Flow's enumeration.
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

    /**
     * Renders one block per observation name, with the full sorted set of span
     * names the scenarios produced. The attribute keys must not depend on which
     * span name a sample took, so key sets are required to agree across every
     * snapshot of the same observation.
     */
    private static String render(List<Snapshot> snapshots) {
        Map<String, List<Snapshot>> byName = snapshots.stream()
                .collect(Collectors.groupingBy(Snapshot::name, TreeMap::new,
                        Collectors.toList()));
        StringBuilder out = new StringBuilder();
        byName.forEach((name, group) -> {
            Set<String> spanNames = group.stream().map(Snapshot::spanName)
                    .collect(Collectors.toCollection(TreeSet::new));
            // Keyed on the (low, high) pair: keying a Map on the low set
            // alone would let a divergent high set overwrite silently.
            Set<List<Set<String>>> keyShapes = group.stream()
                    .map(s -> List.of(s.lowCardinalityKeys(),
                            s.highCardinalityKeys()))
                    .collect(Collectors.toSet());
            Assertions.assertEquals(1, keyShapes.size(),
                    name + " produced differing attribute key sets across "
                            + "span names; the golden format assumes one key "
                            + "shape per observation, so drive the scenario "
                            + "with every optional attribute present");
            Snapshot first = group.get(0);
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(name).append('\n') //
                    .append("  span-names: ")
                    .append(String.join(", ", spanNames)).append('\n') //
                    .append("  low:  ")
                    .append(keyList(first.lowCardinalityKeys())).append('\n') //
                    .append("  high: ")
                    .append(keyList(first.highCardinalityKeys())).append('\n');
        });
        return out.toString();
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
            // Strip the explanatory header, and normalize the edges so an
            // accidental leading or trailing blank line cannot produce a
            // mismatch that is invisible in the failure diff.
            return content.lines().filter(line -> !line.startsWith("#"))
                    .collect(Collectors.joining("\n")).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
