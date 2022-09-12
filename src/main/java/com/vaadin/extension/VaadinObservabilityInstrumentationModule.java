package com.vaadin.extension;

import static com.vaadin.extension.InstrumentationHelper.INSTRUMENTATION_VERSION;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static java.util.Arrays.asList;

import com.vaadin.extension.instrumentation.AbstractNavigationStateRendererInstrumentation;
import com.vaadin.extension.instrumentation.DataCommunicatorInstrumentation;
import com.vaadin.extension.instrumentation.communication.HeartbeatHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.JavaScriptBootstrapHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.PwaHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.SessionRequestHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.StreamRequestHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.UidlRequestHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.UnsupportedBrowserHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.WebComponentProviderInstrumentation;
import com.vaadin.extension.instrumentation.communication.WebcomponentBootstrapHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.rpc.AttachExistingElementRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.rpc.AttachTemplateChildRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.rpc.EventRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.rpc.MapSyncRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.rpc.NavigationRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.rpc.PublishedServerEventHandlerRpcHandlerInstrumentation;
import com.vaadin.extension.instrumentation.communication.rpc.ReturnChannelHandlerInstrumentation;
import com.vaadin.extension.instrumentation.server.ErrorHandlerInstrumentation;
import com.vaadin.extension.instrumentation.server.StaticFileServerInstrumentation;
import com.vaadin.extension.instrumentation.server.VaadinServiceInstrumentation;
import com.vaadin.extension.instrumentation.server.VaadinSessionInstrumentation;
import com.vaadin.pro.licensechecker.BuildType;
import com.vaadin.pro.licensechecker.LicenseChecker;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.List;

@AutoService(InstrumentationModule.class)
public class VaadinObservabilityInstrumentationModule
        extends InstrumentationModule {

    static {
        LicenseChecker.checkLicenseFromStaticBlock("vaadin-observability",
                INSTRUMENTATION_VERSION, BuildType.PRODUCTION);
    }

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
        // TypeInstrumentation for this instrumentation module
        // @formatter:off
        return asList(
                // This would be the actual request start for the application, but it only wraps to VaadinRequest and VaadinResponse
                // Skipping this will have StaticFileRequest handle create a single span.
//                new VaadinServletInstrumentation(),
                new AttachTemplateChildRpcHandlerInstrumentation(),
                new WebcomponentBootstrapHandlerInstrumentation(),
                new WebComponentProviderInstrumentation(),
                new AbstractNavigationStateRendererInstrumentation(),
                new StreamRequestHandlerInstrumentation(),
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
                new UnsupportedBrowserHandlerInstrumentation(),
                new ReturnChannelHandlerInstrumentation(),
                new VaadinSessionInstrumentation(),
                new ErrorHandlerInstrumentation(),
                new DataCommunicatorInstrumentation()
        );
        // @formatter:on
    }
}
