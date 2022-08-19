package com.vaadin.extension;

import com.google.auto.service.AutoService;

import com.vaadin.extension.instrumentation.AfterNavigationStateRendererInstrumentation;
import com.vaadin.extension.instrumentation.EventRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.NavigationRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.UiInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.List;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static java.util.Arrays.asList;

@AutoService(InstrumentationModule.class)
public class VaadinObservabilityInstrumentationModule extends InstrumentationModule {
    // The instrumentation names should reflect what is in `settings.gradle` `rootProject.name`
    public static final String INSTRUMENTATION_NAME = "vaadin-observability";
    public static final String EXTENDED_NAME = "opentelemetry-vaadin-observability-instrumentation-extension-1.0";

    public VaadinObservabilityInstrumentationModule() {
        super(INSTRUMENTATION_NAME, EXTENDED_NAME);
    }

    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        // class added in vaadin 14.2
        return hasClassesNamed("com.vaadin.flow.server.frontend.installer.NodeInstaller");
    }

    @Override
    public boolean isHelperClass(String className) {
        // TODO: check if helper classes can be included by convention
        return className != null && className.startsWith("com.vaadin.extension");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        // TypeIntrumentation for this instrumentation module
        return asList(
                new AfterNavigationStateRendererInstrumentation(),
                new UiInstrumentation(),
                new EventRpcHandlerInstrumentation(),
                new NavigationRpcHandlerInstrumentation()
        );
    }
}
