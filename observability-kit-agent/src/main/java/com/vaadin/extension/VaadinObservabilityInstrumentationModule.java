/*-
 * Copyright (C) 2022 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.extension;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;

import com.vaadin.extension.instrumentation.AbstractNavigationStateRendererInstrumentation;
import com.vaadin.extension.instrumentation.client.ClientInstrumentation;
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
import com.vaadin.extension.instrumentation.data.DataCommunicatorInstrumentation;
import com.vaadin.extension.instrumentation.data.HierarchicalDataProviderInstrumentation;
import com.vaadin.extension.instrumentation.data.renderer.ComponentRendererInstrumentation;
import com.vaadin.extension.instrumentation.server.ErrorHandlerInstrumentation;
import com.vaadin.extension.instrumentation.server.StaticFileServerInstrumentation;
import com.vaadin.extension.instrumentation.server.VaadinServletInstrumentation;
import com.vaadin.extension.instrumentation.server.VaadinSessionInstrumentation;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(InstrumentationModule.class)
public class VaadinObservabilityInstrumentationModule
        extends InstrumentationModule {

    static {
    }

    public static final String INSTRUMENTATION_NAME = "vaadin-observability-kit";
    public static final String EXTENDED_NAME = "opentelemetry-vaadin-observability-instrumentation-extension-"
            + InstrumentationHelper.INSTRUMENTATION_VERSION;

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
        return Stream
                .of(instrumentation(), rpcHandlerInstrumentation(),
                        requestHandlerInstrumentation(), dataInstrumentation(),
                        serverInstrumentation(), clientInstrumentation())
                .flatMap(i -> i).collect(Collectors.toList());
    }

    private Stream<TypeInstrumentation> instrumentation() {
        // @formatter:off
        return Stream.of(new AbstractNavigationStateRendererInstrumentation());
        // @formatter:on
    }

    private Stream<TypeInstrumentation> rpcHandlerInstrumentation() {
        // @formatter:off
        return Stream.of(new AttachExistingElementRpcHandlerInstrumentation(),
                new AttachTemplateChildRpcHandlerInstrumentation(),
                new EventRpcHandlerInstrumentation(),
                new MapSyncRpcHandlerInstrumentation(),
                new NavigationRpcHandlerInstrumentation(),
                new PublishedServerEventHandlerRpcHandlerInstrumentation(),
                new ReturnChannelHandlerInstrumentation());
        // @formatter:on
    }

    private Stream<TypeInstrumentation> requestHandlerInstrumentation() {
        // @formatter:off
        return Stream.of(new HeartbeatHandlerInstrumentation(),
                new JavaScriptBootstrapHandlerInstrumentation(),
                new PwaHandlerInstrumentation(),
                new SessionRequestHandlerInstrumentation(),
                new StreamRequestHandlerInstrumentation(),
                new UidlRequestHandlerInstrumentation(),
                new UnsupportedBrowserHandlerInstrumentation(),
                new WebcomponentBootstrapHandlerInstrumentation(),
                new WebComponentProviderInstrumentation());
        // @formatter:on
    }

    private Stream<TypeInstrumentation> dataInstrumentation() {
        // @formatter:off
        return Stream.of(new ComponentRendererInstrumentation(),
                new DataCommunicatorInstrumentation(),
                new HierarchicalDataProviderInstrumentation());
        // @formatter:on
    }

    private Stream<TypeInstrumentation> serverInstrumentation() {
        // @formatter:off
        return Stream.of(new ErrorHandlerInstrumentation(),
                new StaticFileServerInstrumentation(),
                new VaadinServletInstrumentation(),
                new VaadinSessionInstrumentation());
        // @formatter:on
    }

    private Stream<TypeInstrumentation> clientInstrumentation() {
        // @formatter:off
        return Stream.of(new ClientInstrumentation());
        // @formatter:on
    }
}
