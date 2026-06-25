/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.LoggerFactory;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.internal.StringUtil;

/**
 * Loads a bundled client-side JavaScript resource into a {@link UI} exactly
 * once. The classpath resource is read, stripped of comments and executed via
 * {@link com.vaadin.flow.component.page.Page#executeJs}. A per-UI flag keyed by
 * {@code initKey} guards against repeated injection.
 */
public final class ClientResourceLoader {

    private ClientResourceLoader() {
    }

    /**
     * Injects {@code resource} into {@code ui} once. Subsequent calls with the
     * same {@code initKey} for the same UI are no-ops. Missing resources or
     * read failures are logged against {@code owner} and otherwise ignored.
     *
     * @param ui
     *            the target UI; {@code null} is ignored
     * @param initKey
     *            the per-UI data key used to ensure single injection
     * @param resource
     *            the classpath resource path of the JavaScript to load
     * @param owner
     *            the class whose class loader and logger are used
     */
    public static void loadOnce(UI ui, String initKey, String resource,
            Class<?> owner) {
        if (ui == null || ComponentUtil.getData(ui, initKey) != null) {
            return;
        }
        ComponentUtil.setData(ui, initKey, Boolean.TRUE);
        try (InputStream in = owner.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                LoggerFactory.getLogger(owner).warn(
                        "observability-kit client resource not found: {}",
                        resource);
                return;
            }
            String js = StringUtil.removeComments(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    true);
            ui.getPage().executeJs(js);
        } catch (IOException e) {
            LoggerFactory.getLogger(owner).warn(
                    "Could not load observability-kit client resource: {}",
                    resource, e);
        }
    }
}
