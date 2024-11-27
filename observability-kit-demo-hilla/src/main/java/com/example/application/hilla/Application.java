package com.example.application.hilla;

import java.io.IOException;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.Theme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point of the Spring Boot application.
 *
 * Use the @PWA annotation make the application installable on phones, tablets
 * and some desktop browsers.
 *
 */
@SpringBootApplication
@Theme(value = "observability-kit-hilla-demo")
public class Application implements AppShellConfigurator {

    public static void main(String[] args) throws IOException {
        SpringApplication.run(Application.class, args);
    }

}
