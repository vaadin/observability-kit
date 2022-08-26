package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.ContextKeys;
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
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.util.ArrayList;

public abstract class AbstractInstrumentationTest {

    private UI mockUI;
    private VaadinSession mockSession;
    private VaadinService mockService;
    private Scope sessionScope;

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
    public void resetSpans() {
        OpenTelemetryTestTools.getSpanBuilderCapture().reset();
        OpenTelemetryTestTools.getSpanExporter().reset();
    }

    @BeforeEach
    public void setupMockUi() {
        mockUI = new UI();

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
    }

    @BeforeEach
    public void openSessionContext() {
        sessionScope = Context.current()
                .with(ContextKeys.SESSION_ID, getMockSessionId()).makeCurrent();
    }

    @AfterEach
    public void closeSessionContext() {
        sessionScope.close();
    }

    protected RootContextScope withRootContext() {
        return new RootContextScope();
    }

    protected Span getCapturedSpan(int index) {
        return OpenTelemetryTestTools.getSpanBuilderCapture().getSpan(index);
    }

    protected SpanData getExportedSpan(int index) {
        return OpenTelemetryTestTools.getSpanExporter().getSpan(index);
    }

    protected int getExportedSpanCount() {
        return OpenTelemetryTestTools.getSpanExporter().getSpans().size();
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
                    .newInstrumenter();
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
