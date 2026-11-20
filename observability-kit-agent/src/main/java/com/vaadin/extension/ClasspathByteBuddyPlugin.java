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

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;

import io.opentelemetry.javaagent.tooling.muzzle.generation.MuzzleCodeGenerationPlugin;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType.Builder;

/**
 * Wrapper for OpenTelemetry {@link MuzzleCodeGenerationPlugin} to provide the
 * current classpath and instrument the instrumentation module.
 */
public class ClasspathByteBuddyPlugin implements Plugin {

    private final Plugin delegate;

    /**
     * Creates the plugin instance.
     */
    public ClasspathByteBuddyPlugin()
            throws MalformedURLException, URISyntaxException {
        URLClassLoader cl = (URLClassLoader) getClass().getClassLoader();
        URL[] urls = cl.getURLs();
        File[] classpath = new File[urls.length];
        for (int i = 0; i < urls.length; i++) {
            classpath[i] = new File(urls[i].toURI());
        }
        delegate = new MuzzleCodeGenerationPlugin(classpath);
    }

    @Override
    public boolean matches(TypeDescription target) {
        return delegate.matches(target);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public Builder<?> apply(Builder<?> builder, TypeDescription typeDescription,
            ClassFileLocator classFileLocator) {
        return delegate.apply(builder, typeDescription, classFileLocator);
    }
}
