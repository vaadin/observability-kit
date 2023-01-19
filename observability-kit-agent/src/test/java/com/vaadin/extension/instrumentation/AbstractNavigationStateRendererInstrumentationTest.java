package com.vaadin.extension.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.NavigationEvent;
import com.vaadin.flow.router.NavigationTrigger;
import com.vaadin.flow.router.RouteConfiguration;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;

class AbstractNavigationStateRendererInstrumentationTest
        extends AbstractInstrumentationTest {

    private NavigationEvent navigationEvent;

    @BeforeEach
    public void setup() {
        RouteConfiguration.forSessionScope().setRoute("new-route",
                NewRouteView.class);

        Location location = new Location("new-route");
        navigationEvent = Mockito.mock(NavigationEvent.class);
        Mockito.when(navigationEvent.getLocation()).thenReturn(location);
        Mockito.when(navigationEvent.getTrigger())
                .thenReturn(NavigationTrigger.CLIENT_SIDE);
        Mockito.when(navigationEvent.isForwardTo()).thenReturn(true);
        Mockito.when(navigationEvent.getUI()).thenReturn(getMockUI());
    }

    @Test
    public void handle_createsSpan() {
        AbstractNavigationStateRendererInstrumentation.HandleAdvice
                .onEnter(navigationEvent, null, null);
        AbstractNavigationStateRendererInstrumentation.HandleAdvice.onExit(null,
                navigationEvent, getCapturedSpan(0), null);

        SpanData span = getExportedSpan(0);
        assertEquals("Navigate: /new-route", span.getName());
        assertEquals("CLIENT_SIDE", span.getAttributes()
                .get(AttributeKey.stringKey("vaadin.navigation.trigger")));
        assertEquals(true, span.getAttributes()
                .get(AttributeKey.booleanKey("vaadin.navigation.isForwardTo")));
    }

    @Test
    public void handle_updatesRootSpan() {
        getMockUI().getInternals().showRouteTarget(new Location("new-route"),
                new NewRouteView(), new ArrayList<>());

        try (var ignored = withRootContext()) {
            AbstractNavigationStateRendererInstrumentation.HandleAdvice
                    .onEnter(navigationEvent, null, null);
            AbstractNavigationStateRendererInstrumentation.HandleAdvice
                    .onExit(null, navigationEvent, getCapturedSpan(0), null);
        }

        SpanData span = getExportedSpan(1);
        assertEquals("/new-route", span.getName());
    }

    @Tag("new-route")
    protected static class NewRouteView extends Component {
    }
}