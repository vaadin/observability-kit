package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vaadin.extension.instrumentation.util.OpenTelemetryTestTools;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.function.DeploymentConfiguration;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.util.ArrayList;

public abstract class AbstractInstrumentationTest {

    private UI mockUI;

    public UI getMockUI() {
        return mockUI;
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

        DeploymentConfiguration deploymentConfiguration = Mockito
                .mock(DeploymentConfiguration.class);
        VaadinService service = Mockito.mock(VaadinService.class);
        Mockito.when(service.getDeploymentConfiguration())
                .thenReturn(deploymentConfiguration);

        VaadinSession session = Mockito.spy(new VaadinSession(service));
        Mockito.doNothing().when(session).checkHasLock();
        VaadinSession.setCurrent(session);
        mockUI.getInternals().setSession(session);

        RouteConfiguration.forSessionScope().setRoute("test-route",
                TestView.class);
        mockUI.getInternals().showRouteTarget(new Location("test-route"),
                new TestView(), new ArrayList<>());
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
