package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.extension.ContextKeys;
import com.vaadin.extension.conf.Configuration;
import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.extension.instrumentation.util.MockVaadinService;
import com.vaadin.extension.instrumentation.util.OpenTelemetryTestTools;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.sdk.metrics.data.GaugeData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractInstrumentationTest {

    private UI mockUI;
    private MockedStatic<UI> UiStaticMock;
    private VaadinSession mockSession;
    private VaadinService mockService;
    private Scope sessionScope;
    private MockedStatic<Configuration> ConfigurationMock;
    private TraceLevel configuredTraceLevel;

    public UI getMockUI() {
        return mockUI;
    }

    public VaadinSession getMockSession() {
        return mockSession;
    }

    public VaadinService getMockService() {
        return mockService;
    }

    public String getMockSessionId() {
        return "mock-session-id";
    }

    @BeforeAll
    public static void setupOpenTelemetry() {
        OpenTelemetryTestTools.ensureSetup();
    }

    @BeforeEach
    public void setupMocks() {
        // Reset span data
        resetSpans();

        // Setup mock UI
        mockUI = new UI();
        UiStaticMock = Mockito.mockStatic(UI.class);
        UiStaticMock.when(UI::getCurrent).thenReturn(mockUI);

        mockService = new MockVaadinService(mockUI);

        mockSession = Mockito.spy(new VaadinSession(mockService));
        Mockito.doNothing().when(mockSession).checkHasLock();
        VaadinSession.setCurrent(mockSession);
        Mockito.when(mockSession.getService()).thenReturn(mockService);
        mockUI.getInternals().setSession(mockSession);

        RouteConfiguration.forSessionScope().setRoute("test-route",
                TestView.class);
        mockUI.getInternals().showRouteTarget(new Location("test-route"),
                new TestView(), new ArrayList<>());

        // Add session to context
        sessionScope = Context.current()
                .with(ContextKeys.SESSION_ID, getMockSessionId()).makeCurrent();

        // Mock configuration static
        configuredTraceLevel = TraceLevel.DEFAULT;
        ConfigurationMock = Mockito.mockStatic(Configuration.class);
        ConfigurationMock.when(() -> Configuration.isEnabled(Mockito.any()))
                .thenAnswer(invocation -> {
                    TraceLevel level = invocation.getArgument(0);
                    return configuredTraceLevel.includes(level);
                });
    }

    @AfterEach
    public void cleanupMocks() {
        sessionScope.close();
        UiStaticMock.close();
        ConfigurationMock.close();
    }

    protected RootContextScope withRootContext() {
        return new RootContextScope();
    }

    protected void resetSpans() {
        OpenTelemetryTestTools.getSpanBuilderCapture().reset();
        OpenTelemetryTestTools.getSpanExporter().reset();
    }

    protected Span getCapturedSpan(int index) {
        return OpenTelemetryTestTools.getSpanBuilderCapture().getSpan(index);
    }

    protected Span getCapturedSpanOrNull(int index) {
        return OpenTelemetryTestTools.getSpanBuilderCapture()
                .getSpanOrNull(index);
    }

    protected int getCapturedSpanCount() {
        return OpenTelemetryTestTools.getSpanBuilderCapture().getSpans().size();
    }

    protected SpanData getExportedSpan(int index) {
        return OpenTelemetryTestTools.getSpanExporter().getSpan(index);
    }

    protected int getExportedSpanCount() {
        return OpenTelemetryTestTools.getSpanExporter().getSpans().size();
    }

    protected void readMetrics() {
        OpenTelemetryTestTools.getMetricReader().read();
    }

    protected MetricData getMetric(String name) {
        readMetrics();
        return OpenTelemetryTestTools.getMetricReader().getMetric(name);
    }

    protected long getLastLongGaugeMetricValue(String name) {
        MetricData metric = getMetric(name);
        GaugeData<LongPointData> longGaugeData = metric.getLongGaugeData();
        List<LongPointData> points = new ArrayList<>(longGaugeData.getPoints());

        assertTrue(points.size() > 0, "Metric does not have any recorded data");

        return points.get(points.size() - 1).getValue();
    }

    protected HistogramPointData getLastHistogramMetricValue(String name) {
        MetricData metric = getMetric(name);
        List<HistogramPointData> points = new ArrayList<>(
                metric.getHistogramData().getPoints());

        assertTrue(points.size() > 0, "Metric does not have any recorded data");

        return points.get(points.size() - 1);
    }

    protected void assertSpanHasException(SpanData span, Throwable throwable) {
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals(throwable.getMessage(), span.getStatus().getDescription());

        assertEquals(1, span.getEvents().size());
        EventData eventData = span.getEvents().get(0);
        assertEquals(throwable.getClass().getCanonicalName(), eventData
                .getAttributes().get(SemanticAttributes.EXCEPTION_TYPE));
        assertEquals(throwable.getMessage(), eventData.getAttributes()
                .get(SemanticAttributes.EXCEPTION_MESSAGE));
    }

    protected void configureTraceLevel(TraceLevel level) {
        configuredTraceLevel = level;
    }

    @Tag("test-view")
    protected static class TestView extends Component {
    }

    protected static class RootContextScope implements AutoCloseable {
        private final Scope scope;
        private final Instrumenter<Object, Object> rootInstrumenter;
        private final Context rootContext;

        public RootContextScope() {
            rootInstrumenter = Instrumenter
                    .builder(GlobalOpenTelemetry.get(), "test",
                            RootContextScope::getRootSpanName)
                    .buildInstrumenter();
            rootContext = rootInstrumenter.start(Context.root(), null);
            scope = rootContext.makeCurrent();
        }

        private static String getRootSpanName(Object object) {
            return "/";
        }

        @Override
        public void close() {
            scope.close();
            rootInstrumenter.end(rootContext, null, null, null);
        }
    }
}
