package com.vaadin.extension.instrumentation;

import static io.opentelemetry.semconv.ExceptionAttributes.EXCEPTION_MESSAGE;
import static io.opentelemetry.semconv.ExceptionAttributes.EXCEPTION_TYPE;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.platform.commons.util.ReflectionUtils;
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
        OpenTelemetryTestTools.getMetricReader().collectAllMetrics();
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
                .getAttributes().get(EXCEPTION_TYPE));
        assertEquals(throwable.getMessage(), eventData.getAttributes()
                .get(EXCEPTION_MESSAGE));
    }

    protected void configureTraceLevel(TraceLevel level) {
        configuredTraceLevel = level;
    }

    /**
     * Patches the OpenTelemetry context storage to set a specific context.
     * <p>
     * This is necessary for testing instrumentations that create a sub-context
     * during their execution. Since our test setup does not support closing
     * these sub-contexts, this method allows forcing a specific context to be
     * the current. This allows to either clear stale contexts between test
     * runs, or roll back to a previous / parent context.
     *
     * @param context
     *            the context to set as the current one, or {@code null} to
     *            clear the current context
     */
    protected void fixCurrentContext(Context context) {
        final var tlcs = "io.opentelemetry.context.ThreadLocalContextStorage";
        final var fieldName = "THREAD_LOCAL_STORAGE";
        ReflectionUtils.tryToLoadClass(tlcs).ifSuccess(ctxClass -> {
            try {
                final var field = ctxClass.getDeclaredField(fieldName);
                field.setAccessible(true);
                final var tl = (ThreadLocal<Context>) field.get(null);

                if (context != null) {
                    tl.set(context);
                } else {
                    tl.remove();
                }
            } catch (NoSuchFieldException | SecurityException
                    | IllegalArgumentException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }).ifFailure(e -> {
            e.printStackTrace();
        });
    }

    @Tag("test-view")
    protected static class TestView extends Component {
    }

    protected class RootContextScope implements AutoCloseable {
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
            // Roll back to the root context we created, otherwise our root span
            // will not be created
            fixCurrentContext(rootContext);
            scope.close();
            rootInstrumenter.end(rootContext, null, null, null);
        }
    }
}
