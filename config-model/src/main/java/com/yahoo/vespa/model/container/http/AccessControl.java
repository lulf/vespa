// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.container.http;

import com.yahoo.component.ComponentId;
import com.yahoo.component.ComponentSpecification;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.vespa.model.container.ApplicationContainerCluster;
import com.yahoo.vespa.model.container.ContainerCluster;
import com.yahoo.vespa.model.container.component.FileStatusHandlerComponent;
import com.yahoo.vespa.model.container.component.Handler;
import com.yahoo.vespa.model.container.component.Servlet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper class for http access control.
 *
 * @author gjoranv
 * @author bjorncs
 */
public final class AccessControl {

    public static final ComponentId ACCESS_CONTROL_CHAIN_ID = ComponentId.fromString("access-control-chain");

    public static final List<String> UNPROTECTED_HANDLERS = List.of(
            FileStatusHandlerComponent.CLASS,
            ContainerCluster.APPLICATION_STATUS_HANDLER_CLASS,
            ContainerCluster.BINDINGS_OVERVIEW_HANDLER_CLASS,
            ContainerCluster.STATE_HANDLER_CLASS,
            ContainerCluster.LOG_HANDLER_CLASS,
            ApplicationContainerCluster.METRICS_V2_HANDLER_CLASS,
            ApplicationContainerCluster.PROMETHEUS_V1_HANDLER_CLASS
    );

    public static final class Builder {
        private String domain;
        private boolean readEnabled = false;
        private boolean writeEnabled = true;
        private final Set<String> excludeBindings = new LinkedHashSet<>();
        private Collection<Handler<?>> handlers = Collections.emptyList();
        private Collection<Servlet> servlets = Collections.emptyList();
        private final DeployLogger logger;

        public Builder(String domain, DeployLogger logger) {
            this.domain = domain;
            this.logger = logger;
        }

        public Builder readEnabled(boolean readEnabled) {
            this.readEnabled = readEnabled;
            return this;
        }

        public Builder writeEnabled(boolean writeEnalbed) {
            this.writeEnabled = writeEnalbed;
            return this;
        }

        public Builder excludeBinding(String binding) {
            this.excludeBindings.add(binding);
            return this;
        }

        public Builder setHandlers(ApplicationContainerCluster cluster) {
            this.handlers = cluster.getHandlers();
            this.servlets = cluster.getAllServlets();
            return this;
        }

        public AccessControl build() {
            return new AccessControl(domain, writeEnabled, readEnabled,
                                     excludeBindings, servlets, handlers, logger);
        }
    }

    public final String domain;
    public final boolean readEnabled;
    public final boolean writeEnabled;
    private final Set<String> excludedBindings;
    private final Collection<Handler<?>> handlers;
    private final Collection<Servlet> servlets;
    private final DeployLogger logger;

    private AccessControl(String domain,
                          boolean writeEnabled,
                          boolean readEnabled,
                          Set<String> excludedBindings,
                          Collection<Servlet> servlets,
                          Collection<Handler<?>> handlers,
                          DeployLogger logger) {
        this.domain = domain;
        this.readEnabled = readEnabled;
        this.writeEnabled = writeEnabled;
        this.excludedBindings = Collections.unmodifiableSet(excludedBindings);
        this.handlers = handlers;
        this.servlets = servlets;
        this.logger = logger;
    }

    public List<FilterBinding> getBindings() {
        return Stream.concat(getHandlerBindings(), getServletBindings())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static boolean hasHandlerThatNeedsProtection(ApplicationContainerCluster cluster) {
        return cluster.getHandlers().stream().anyMatch(AccessControl::handlerNeedsProtection);
    }

    private Stream<FilterBinding> getHandlerBindings() {
        return handlers.stream()
                        .filter(this::shouldHandlerBeProtected)
                        .flatMap(handler -> handler.getServerBindings().stream())
                        .map(binding -> accessControlBinding(binding, logger));
    }

    private Stream<FilterBinding> getServletBindings() {
        return servlets.stream()
                .filter(this::shouldServletBeProtected)
                .flatMap(AccessControl::servletBindings)
                .map(binding -> accessControlBinding(binding, logger));
    }

    private boolean shouldHandlerBeProtected(Handler<?> handler) {
        return ! isBuiltinGetOnly(handler)
                && handler.getServerBindings().stream().noneMatch(excludedBindings::contains);
    }

    private static boolean isBuiltinGetOnly(Handler<?> handler) {
        return UNPROTECTED_HANDLERS.contains(handler.getClassId().getName());
    }

    private boolean shouldServletBeProtected(Servlet servlet) {
        return servletBindings(servlet).noneMatch(excludedBindings::contains);
    }

    private static FilterBinding accessControlBinding(String binding, DeployLogger logger) {
        return FilterBinding.create(new ComponentSpecification(ACCESS_CONTROL_CHAIN_ID.stringValue()), binding, logger);
    }

    private static Stream<String> servletBindings(Servlet servlet) {
        return Stream.of("http://*/").map(protocol -> protocol + servlet.bindingPath);
    }

    private static boolean handlerNeedsProtection(Handler<?> handler) {
        return ! isBuiltinGetOnly(handler) && hasNonMbusBinding(handler);
    }

    private static boolean hasNonMbusBinding(Handler<?> handler) {
        return handler.getServerBindings().stream().anyMatch(binding -> ! binding.startsWith("mbus"));
    }

}
