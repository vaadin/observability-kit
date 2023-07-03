package dev.hilla.observability.test.endpoints;

import dev.hilla.Endpoint;

import com.vaadin.flow.server.auth.AnonymousAllowed;

@Endpoint
@AnonymousAllowed
public class MainEndpoint {
    public String test() {
        return "foo";
    }
}
