/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.micrometer;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.dom.ElementUtil;
import com.vaadin.flow.internal.StateNode;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.router.Router;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.server.RouteRegistry;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

/**
 * Measures how much state one UI holds on the server.
 * <p>
 * Flow keeps every open browser tab's component tree in server memory, so for a
 * server-driven application the number that predicts when to add capacity is
 * not how many users are connected but how much state each of them costs: a
 * hundred users on a dashboard with three grids cost nothing like a hundred
 * users on a login form.
 * <p>
 * The count comes from Flow's own state tree
 * ({@link StateNode#visitNodeTree(java.util.function.Consumer)}) rather than
 * from an {@code Element.getChildren()} walk, because the public walk cannot
 * reach <em>virtual</em> children — and in Flow 25 the route target is attached
 * to its UI as a virtual child, so a public walk of a live UI finds a couple of
 * nodes and no view at all. Overlays, dialogs and grid editors are invisible
 * the same way. Walking the state tree is also the only pass that can see the
 * components and retained views counted here.
 * <p>
 * <strong>Views.</strong> A component counts as a view when it is part of the
 * UI's active router-target chain, when it is a {@link RouterLayout}, or when
 * its class — or a superclass, so that subclasses and DI proxies are not missed
 * — is registered as a route. The route registry rather than the {@code @Route}
 * annotation is asked, so a route added through
 * {@link RouteConfiguration#setRoute} counts like an annotated one. Views
 * <em>outside</em> the active chain are counted separately: that, and not the
 * plain view count, is the signal that views outlive their navigation, because
 * one navigation into a nested layout legitimately retains a view per level.
 * <p>
 * <strong>Threading:</strong> a state tree may only be read while its own
 * session's lock is held, so a UI is always sampled by itself — from a request,
 * navigation or RPC belonging to that session — never by another user's request
 * thread walking into it. {@link UiStateMetricsBinder} is built around that
 * rule.
 * <p>
 * <strong>What this does not measure:</strong> bytes. A node count is a proxy
 * for retained heap, not a measurement of it — one {@code Grid} node backed by
 * 100 000 rows counts as a single node. See
 * {@link ObservabilitySettings#getUiStateBytesPerNode()} for turning nodes into
 * a byte figure with a cost measured for the application at hand.
 */
final class UiStateSampler {

    private UiStateSampler() {
    }

    /**
     * Measures one UI. Must be called on a thread holding that UI's session
     * lock.
     *
     * @param ui
     *            the UI to measure, not {@code null}
     * @return the measurement
     */
    static UiStateSample sample(UI ui) {
        StateWalk walk = new StateWalk(activeChain(ui), routeRegistry(ui));
        ui.getElement().getNode().visitNodeTree(walk::visit);
        return new UiStateSample(walk.nodes, walk.components, walk.views,
                walk.staleViews, System.nanoTime());
    }

    /**
     * The route targets and layouts the UI is currently showing, by identity: a
     * view is compared against these to tell a live one from one that outlived
     * its navigation.
     */
    private static Set<Component> activeChain(UI ui) {
        Set<Component> active = Collections
                .newSetFromMap(new IdentityHashMap<>());
        for (HasElement target : ui.getInternals()
                .getActiveRouterTargetsChain()) {
            if (target instanceof Component component) {
                active.add(component);
            }
        }
        return active;
    }

    /**
     * The registry that knows which classes are routes, or {@code null} when
     * the UI has no router to ask — then nothing outside the active chain
     * counts as a view unless it is a {@link RouterLayout}. Resolved step by
     * step because measuring must not be what fails on a half-built UI.
     */
    private static RouteRegistry routeRegistry(UI ui) {
        VaadinSession session = ui.getSession();
        VaadinService service = session == null ? null : session.getService();
        Router router = service == null ? null : service.getRouter();
        return router == null ? null : router.getRegistry();
    }

    /**
     * One pass over the state tree, counting nodes, the components attached to
     * them, and the views among those components.
     */
    private static final class StateWalk {

        private final Set<Component> activeChain;
        private final RouteConfiguration routes;
        /**
         * Whether a component class is a route, memoized for the duration of
         * this walk: a tree holds many components of few classes, and the
         * lookup climbs a class hierarchy. Per walk rather than static, so a
         * route registered at runtime is seen by the next sample.
         */
        private final Map<Class<?>, Boolean> routeClasses = new HashMap<>();

        private int nodes;
        private int components;
        private int views;
        private int staleViews;

        StateWalk(Set<Component> activeChain, RouteRegistry registry) {
            this.activeChain = activeChain;
            this.routes = registry == null ? null
                    : RouteConfiguration.forRegistry(registry);
        }

        private void visit(StateNode node) {
            nodes++;
            ElementUtil.from(node).flatMap(Element::getComponent)
                    .ifPresent(this::countComponent);
        }

        private void countComponent(Component component) {
            components++;
            if (activeChain.contains(component)) {
                views++;
            } else if (isView(component)) {
                views++;
                staleViews++;
            }
        }

        /**
         * Whether a component outside the active chain is a view. A
         * {@link RouterLayout} is one whether or not it is itself routable;
         * anything else has to be a registered route target.
         */
        private boolean isView(Component component) {
            if (component instanceof UI) {
                // The UI is a RouterLayout — it is what a chain is rooted in,
                // not something a navigation put there.
                return false;
            }
            if (component instanceof RouterLayout) {
                return true;
            }
            return routes != null && routeClasses
                    .computeIfAbsent(component.getClass(), this::isRouteClass);
        }

        /**
         * Whether the class or one of its superclasses is registered as a
         * route. The hierarchy is climbed because {@code @Route} is not
         * inherited and Flow registers the annotated class, so an instance of a
         * subclass — a DI proxy, or a view specialized per tenant — is a route
         * target under a class name the registry has never seen.
         */
        private boolean isRouteClass(Class<?> type) {
            for (Class<?> current = type; Component.class.isAssignableFrom(
                    current); current = current.getSuperclass()) {
                if (routes.isRouteRegistered(
                        current.asSubclass(Component.class))) {
                    return true;
                }
            }
            return false;
        }
    }
}
