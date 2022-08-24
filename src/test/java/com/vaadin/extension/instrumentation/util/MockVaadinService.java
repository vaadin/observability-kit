package com.vaadin.extension.instrumentation.util;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.*;

import org.mockito.Mockito;

import java.io.InputStream;
import java.net.URL;

public class MockVaadinService extends VaadinService {
    private final RouteRegistry routeRegistry = Mockito
            .mock(RouteRegistry.class);
    private final DeploymentConfiguration deploymentConfiguration = Mockito
            .mock(DeploymentConfiguration.class);

    @Override
    protected RouteRegistry getRouteRegistry() {
        return routeRegistry;
    }

    @Override
    protected PwaRegistry getPwaRegistry() {
        return null;
    }

    @Override
    public String getContextRootRelativePath(VaadinRequest request) {
        return null;
    }

    @Override
    public String getMimeType(String resourceName) {
        return null;
    }

    @Override
    protected boolean requestCanCreateSession(VaadinRequest request) {
        return false;
    }

    @Override
    public String getServiceName() {
        return null;
    }

    @Override
    public String getMainDivId(VaadinSession session, VaadinRequest request) {
        return null;
    }

    @Override
    public URL getStaticResource(String url) {
        return null;
    }

    @Override
    public URL getResource(String url) {
        return null;
    }

    @Override
    public InputStream getResourceAsStream(String url) {
        return null;
    }

    @Override
    public String resolveResource(String url) {
        return null;
    }

    @Override
    protected VaadinContext constructVaadinContext() {
        return null;
    }

    @Override
    public DeploymentConfiguration getDeploymentConfiguration() {
        return deploymentConfiguration;
    }
}
