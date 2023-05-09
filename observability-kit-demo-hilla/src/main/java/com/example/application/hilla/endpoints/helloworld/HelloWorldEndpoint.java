package com.example.application.hilla.endpoints.helloworld;

import com.vaadin.flow.server.auth.AnonymousAllowed;
import dev.hilla.Endpoint;

@Endpoint
@AnonymousAllowed
public class HelloWorldEndpoint {
    public String sayHello(String name) {
        if (name.isEmpty()) {
            return "Hello stranger";
        } else {
            return "Hello " + name;
        }
    }

    public String runLongTask(String name) {
        try {
            Thread.sleep(3000);
            return "Job completed for " + name;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
