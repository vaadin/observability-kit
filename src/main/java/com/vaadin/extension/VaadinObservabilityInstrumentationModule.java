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
import com.vaadin.extension.instrumentation.PushAtmosphereHandlerInstrumentation;
import com.vaadin.extension.instrumentation.PushHandlerInstrumentation;
import com.vaadin.extension.instrumentation.PushRequestHandlerInstrumentation;
import com.vaadin.extension.instrumentation.PwaHandlerInstrumentation;
import com.vaadin.extension.instrumentation.ReturnChannelHandlerInstrumentation;
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

import java.util.ArrayList;
import java.util.Collections;
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
        final List<TypeInstrumentation> typeInstrumentations = new ArrayList<>();
        addFeatureInstrumentation(typeInstrumentations);
        addRequestHandlers(typeInstrumentations);
        addRpcHandlers(typeInstrumentations);
        return Collections.unmodifiableList(typeInstrumentations);
    }

    private void addFeatureInstrumentation(
            List<TypeInstrumentation> instrumentationList) {
        // @formatter:off
        instrumentationList.addAll(asList(
                new AfterNavigationStateRendererInstrumentation(),
                new StaticFileServerInstrumentation(),
                new VaadinServiceInstrumentation(),
                new PushHandlerInstrumentation(),
                new PushAtmosphereHandlerInstrumentation()
        ));
        // @formatter:on
    }

    private void addRpcHandlers(List<TypeInstrumentation> instrumentationList) {
        // @formatter:off
        instrumentationList.addAll(asList(
                new EventRpcHandlerInstrumentation(),
                new NavigationRpcHandlerInstrumentation(),
                new MapSyncRpcHandlerInstrumentation(),
                new AttachExistingElementRpcHandlerInstrumentation(),
                new AttachTemplateChildRpcHandlerInstrumentation(),
                new PublishedServerEventHandlerRpcHandlerInstrumentation()
                ));
        // @formatter:on
    }

    private void addRequestHandlers(
            List<TypeInstrumentation> instrumentationList) {
        // @formatter:off
        instrumentationList.addAll(asList(
                new PushRequestHandlerInstrumentation(),
                new WebcomponentBootstrapHandlerInstrumentation(),
                new WebComponentProviderInstrumentation(),
                new JavaScriptBootstrapHandlerInstrumentation(),
                new SessionRequestHandlerInstrumentation(),
                new HeartbeatHandlerInstrumentation(),
                new UidlRequestHandlerInstrumentation(),
                new PwaHandlerInstrumentation(),
                new UnsupportedBrowserHandlerInstrumentation(),
                new ReturnChannelHandlerInstrumentation()
                ));
        // @formatter:on
    }
}
