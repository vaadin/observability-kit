package com.vaadin.extension;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static java.util.Arrays.asList;

import com.vaadin.extension.instrumentation.AfterNavigationStateRendererInstrumentation;
import com.vaadin.extension.instrumentation.AttachExistingElementRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.AttachTemplateChildRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.EventRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.HeartbeatHandlerInstrumentation;
import com.vaadin.extension.instrumentation.JavaScriptBootstrapHandlerInstrumentation;
import com.vaadin.extension.instrumentation.MapSyncRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.NavigationRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.PublishedServerEventHandlerRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.PwaHandlerInstrumentation;
import com.vaadin.extension.instrumentation.SessionRequestHandlerInstrumentation;
import com.vaadin.extension.instrumentation.StaticFileServerInstrumentation;
import com.vaadin.extension.instrumentation.UidlRequestHandlerInstrumentation;
import com.vaadin.extension.instrumentation.UnsupportedBrowserHandlerInstrumentation;
import com.vaadin.extension.instrumentation.VaadinServiceInstrumentation;
import com.vaadin.extension.instrumentation.WebComponentProviderInstrumentation;
import com.vaadin.extension.instrumentation.WebcomponentBootstrapHandlerInstrumentation;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.List;

@AutoService(InstrumentationModule.class)
public class VaadinObservabilityInstrumentationModule
        extends InstrumentationModule {
    // The instrumentation names should reflect what is in `settings.gradle`
    // `rootProject.name`
    public static final String INSTRUMENTATION_NAME = "vaadin-observability";
    public static final String EXTENDED_NAME = "opentelemetry-vaadin-observability-instrumentation-extension-1.0";

    public VaadinObservabilityInstrumentationModule() {
        super(INSTRUMENTATION_NAME, EXTENDED_NAME);
    }

    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        // class added in vaadin 14.2
        return hasClassesNamed(
                "com.vaadin.flow.server.frontend.installer.NodeInstaller");
    }

    @Override
    public boolean isHelperClass(String className) {
        // TODO: check if helper classes can be included by convention
        return className != null
                && className.startsWith("com.vaadin.extension");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        // TypeIntrumentation for this instrumentation module
        // @formatter:off
        return asList(
                new AttachTemplateChildRpcHandlerInstrumentation(),
                new WebcomponentBootstrapHandlerInstrumentation(),
                new WebComponentProviderInstrumentation(),
                new AfterNavigationStateRendererInstrumentation(),
                new EventRpcHandlerInstrumentation(),
                new NavigationRpcHandlerInstrumentation(),
                new MapSyncRpcHandlerInstrumentation(),
                new AttachExistingElementRpcHandlerInstrumentation(),
                new JavaScriptBootstrapHandlerInstrumentation(),
                new PublishedServerEventHandlerRpcHandlerInstrumentation(),
                new SessionRequestHandlerInstrumentation(),
                new StaticFileServerInstrumentation(),
                new VaadinServiceInstrumentation(),
                new HeartbeatHandlerInstrumentation(),
                new UidlRequestHandlerInstrumentation(),
                new PwaHandlerInstrumentation(),
                new UnsupportedBrowserHandlerInstrumentation());
        // @formatter:on
    }
}
