/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RouteTagResolverTest {

    private static final class FakeRouteA extends Component {
    }

    private static final class FakeRouteB extends Component {
    }

    private static final class FakeRouteC extends Component {
    }

    @Test
    public void nullTargetIsUnknown() {
        RouteTagResolver resolver = new RouteTagResolver(10);
        assertEquals(MeterNames.ROUTE_UNKNOWN, resolver.tagFor(null));
    }

    @Test
    public void firstRoutesAreAdmittedThenBucketedAsOther() {
        RouteTagResolver resolver = new RouteTagResolver(2);

        String a = resolver.tagFor(FakeRouteA.class);
        String b = resolver.tagFor(FakeRouteB.class);
        String c = resolver.tagFor(FakeRouteC.class);

        assertEquals(FakeRouteA.class.getSimpleName(), a);
        assertEquals(FakeRouteB.class.getSimpleName(), b);
        assertEquals(MeterNames.ROUTE_OTHER, c);
    }

    @Test
    public void admittedRouteRemainsAdmittedAfterCapHit() {
        RouteTagResolver resolver = new RouteTagResolver(1);
        resolver.tagFor(FakeRouteA.class);
        resolver.tagFor(FakeRouteB.class); // overflow -> _other

        assertEquals(FakeRouteA.class.getSimpleName(),
                resolver.tagFor(FakeRouteA.class));
    }
}
