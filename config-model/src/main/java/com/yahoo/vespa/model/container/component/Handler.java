// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.container.component;

import com.yahoo.config.model.producer.AbstractConfigProducer;
import com.yahoo.osgi.provider.model.ComponentModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Models a jdisc RequestHandler (including ClientProvider).
 * RequestHandlers always have at least one server binding,
 * while ClientProviders have at least one client binding.
 * <p>
 * Note that this is also used to model vespa handlers (which do not have any bindings)
 *
 * @author gjoranv
 */
public class Handler<CHILD extends AbstractConfigProducer<?>> extends Component<CHILD, ComponentModel> {

    private Set<String> serverBindings = new LinkedHashSet<>();
    private List<String> clientBindings = new ArrayList<>();

    public Handler(ComponentModel model) {
        super(model);
    }

    public static Handler<AbstractConfigProducer<?>> fromClassName(String className) {
        return new Handler<>(new ComponentModel(className, null, null, null));
    }

    public void addServerBindings(String... bindings) {
        serverBindings.addAll(Arrays.asList(bindings));
    }

    public void removeServerBinding(String binding) {
        serverBindings.remove(binding);
    }

    public void addClientBindings(String... bindings) {
        clientBindings.addAll(Arrays.asList(bindings));
    }

    public final Set<String> getServerBindings() {
        return Collections.unmodifiableSet(serverBindings);
    }

    public final List<String> getClientBindings() {
        return Collections.unmodifiableList(clientBindings);
    }

}
