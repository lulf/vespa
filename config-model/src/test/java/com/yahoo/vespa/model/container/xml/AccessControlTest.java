// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.container.xml;

import com.google.common.collect.ImmutableSet;
import com.yahoo.collections.CollectionUtil;
import com.yahoo.component.ComponentId;
import com.yahoo.config.model.builder.xml.test.DomBuilderTest;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.model.deploy.TestProperties;
import com.yahoo.config.provision.AthenzDomain;
import com.yahoo.container.jdisc.state.StateHandler;
import com.yahoo.vespa.model.container.ApplicationContainer;
import com.yahoo.vespa.model.container.ContainerCluster;
import com.yahoo.vespa.model.container.http.AccessControl;
import com.yahoo.vespa.model.container.http.Http;
import com.yahoo.vespa.model.container.http.FilterBinding;
import com.yahoo.vespa.model.container.http.xml.HttpBuilder;
import com.yahoo.vespa.model.container.jersey.Jersey2Servlet;
import org.junit.Test;
import org.w3c.dom.Element;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.yahoo.config.model.test.TestUtil.joinLines;
import static com.yahoo.vespa.defaults.Defaults.getDefaults;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * @author gjoranv
 */
public class AccessControlTest extends ContainerModelBuilderTestBase {

    private static final Set<String> REQUIRED_HANDLER_BINDINGS = ImmutableSet.of(
            "/custom-handler/",
            "/search/",
            "/document/",
            ContainerCluster.RESERVED_URI_PREFIX);

    private static final Set<String> FORBIDDEN_HANDLER_BINDINGS = ImmutableSet.of(
            "/ApplicationStatus",
            "/status.html",
            "/statistics/",
            StateHandler.STATE_API_ROOT,
            ContainerCluster.ROOT_HANDLER_PATH);

    @Test
    public void access_control_filter_chain_is_set_up() {
        Element clusterElem = DomBuilderTest.parse(
                "  <http>",
                "    <filtering>",
                "      <access-control domain='foo' />",
                "    </filtering>",
                "  </http>");

        Http http = new HttpBuilder().build(root.getDeployState(), root, clusterElem);
        root.freezeModelTopology();

        assertTrue(http.getFilterChains().hasChain(AccessControl.ACCESS_CONTROL_CHAIN_ID));
    }

    @Test
    public void properties_are_set_from_xml() {
        Element clusterElem = DomBuilderTest.parse(
                "  <http>",
                "    <filtering>",
                "      <access-control domain='my-domain'/>",
                "    </filtering>",
                "  </http>");

        Http http = new HttpBuilder().build(root.getDeployState(), root, clusterElem);
        root.freezeModelTopology();
        AccessControl accessControl = http.getAccessControl().get();

        assertEquals("Wrong domain.", "my-domain", accessControl.domain);
    }

    @Test
    public void read_is_disabled_and_write_is_enabled_by_default() {
        Element clusterElem = DomBuilderTest.parse(
                "  <http>",
                "    <filtering>",
                "      <access-control domain='foo' />",
                "    </filtering>",
                "  </http>");

        Http http = new HttpBuilder().build(root.getDeployState(), root, clusterElem);
        root.freezeModelTopology();

        assertFalse("Wrong default value for read.", http.getAccessControl().get().readEnabled);
        assertTrue("Wrong default value for write.", http.getAccessControl().get().writeEnabled);
    }

    @Test
    public void read_and_write_can_be_overridden() {
        Element clusterElem = DomBuilderTest.parse(
                "  <http>",
                "    <filtering>",
                "      <access-control domain='foo' read='true' write='false'/>",
                "    </filtering>",
                "  </http>");

        Http http = new HttpBuilder().build(root.getDeployState(), root, clusterElem);
        root.freezeModelTopology();

        assertTrue("Given read value not honoured.", http.getAccessControl().get().readEnabled);
        assertFalse("Given write value not honoured.", http.getAccessControl().get().writeEnabled);
    }

    @Test
    public void access_control_filter_chain_has_correct_handler_bindings() {
        Element clusterElem = DomBuilderTest.parse(
                "<container version='1.0'>",
                "  <search/>",
                "  <document-api/>",
                "  <handler id='custom.Handler'>",
                "    <binding>http://*/custom-handler/*</binding>",
                "  </handler>",
                "  <http>",
                "    <filtering>",
                "      <access-control domain='foo' />",
                "    </filtering>",
                "  </http>",
                "</container>");

        Http http = getHttp(clusterElem);

        Set<String> foundRequiredBindings = REQUIRED_HANDLER_BINDINGS.stream()
                .filter(requiredBinding -> containsBinding(http.getBindings(), requiredBinding))
                .collect(Collectors.toSet());
        Set<String> missingRequiredBindings = new HashSet<>(REQUIRED_HANDLER_BINDINGS);
        missingRequiredBindings.removeAll(foundRequiredBindings);
        assertTrue("Access control chain was not bound to: " + CollectionUtil.mkString(missingRequiredBindings, ", "),
                   missingRequiredBindings.isEmpty());

        FORBIDDEN_HANDLER_BINDINGS.forEach(forbiddenPath -> {
            String forbiddenBinding = String.format("http://*%s", forbiddenPath);
            http.getBindings().forEach(
                    binding -> assertNotEquals("Access control chain was bound to: " + binding.binding(), binding.binding(), forbiddenBinding));
        });
    }

    @Test
    public void handler_can_be_excluded_by_excluding_one_of_its_bindings() {
        final String notExcludedBinding = "http://*/custom-handler/*";
        final String excludedBinding = "http://*/excluded/*";
        Element clusterElem = DomBuilderTest.parse(
                "<container version='1.0'>",
                httpWithExcludedBinding(excludedBinding),
                "  <handler id='custom.Handler'>",
                "    <binding>" + notExcludedBinding + "</binding>",
                "    <binding>" + excludedBinding + "</binding>",
                "  </handler>",
                "</container>");

        Http http = getHttp(clusterElem);
        assertFalse("Excluded binding was not removed.",
                    containsBinding(http.getBindings(), excludedBinding));
        assertFalse("Not all bindings of an excluded handler were removed.",
                    containsBinding(http.getBindings(), notExcludedBinding));

    }

    @Test
    public void access_control_filter_chain_has_all_servlet_bindings() {
        final String servletPath = "servlet/path";
        final String restApiPath = "api/v0";
        final Set<String> requiredBindings = ImmutableSet.of(servletPath, restApiPath);
        Element clusterElem = DomBuilderTest.parse(
                "<container version='1.0'>",
                "  <servlet id='foo' class='bar' bundle='baz'>",
                "    <path>" + servletPath + "</path>",
                "  </servlet>",
                "  <rest-api jersey2='true' path='" + restApiPath + "' />",
                "  <http>",
                "    <filtering>",
                "      <access-control domain='foo' />",
                "    </filtering>",
                "  </http>",
                "</container>");

        Http http = getHttp(clusterElem);

        Set<String> missingRequiredBindings = requiredBindings.stream()
                .filter(requiredBinding -> ! containsBinding(http.getBindings(), requiredBinding))
                .collect(Collectors.toSet());

        assertTrue("Access control chain was not bound to: " + CollectionUtil.mkString(missingRequiredBindings, ", "),
                   missingRequiredBindings.isEmpty());
    }

    @Test
    public void servlet_can_be_excluded_by_excluding_one_of_its_bindings() {
        final String servletPath = "servlet/path";
        final String notExcludedBinding = "http://*:8081/" + servletPath;
        final String excludedBinding = "http://*:8080/" + servletPath;
        Element clusterElem = DomBuilderTest.parse(
                "<container version='1.0'>",
                httpWithExcludedBinding(excludedBinding),
                "  <servlet id='foo' class='bar' bundle='baz'>",
                "    <path>" + servletPath + "</path>",
                "  </servlet>",
                "</container>");

        Http http = getHttp(clusterElem);
        assertFalse("Excluded binding was not removed.",
                    containsBinding(http.getBindings(), excludedBinding));
        assertFalse("Not all bindings of an excluded servlet were removed.",
                    containsBinding(http.getBindings(), notExcludedBinding));

    }

    @Test
    public void rest_api_can_be_excluded_by_excluding_one_of_its_bindings() {
        final String restApiPath = "api/v0";
        final String notExcludedBinding = "http://*:8081/" + restApiPath + Jersey2Servlet.BINDING_SUFFIX;;
        final String excludedBinding = "http://*:8080/" + restApiPath + Jersey2Servlet.BINDING_SUFFIX;;
        Element clusterElem = DomBuilderTest.parse(
                "<container version='1.0'>",
                httpWithExcludedBinding(excludedBinding),
                "  <rest-api jersey2='true' path='" + restApiPath + "' />",
                "</container>");

        Http http = getHttp(clusterElem);
        assertFalse("Excluded binding was not removed.",
                    containsBinding(http.getBindings(), excludedBinding));
        assertFalse("Not all bindings of an excluded rest-api were removed.",
                    containsBinding(http.getBindings(), notExcludedBinding));

    }


    @Test
    public void access_control_is_implicitly_added_for_hosted_apps() {
        Element clusterElem = DomBuilderTest.parse(
                "<container version='1.0'>",
                nodesXml,
                "</container>" );
        AthenzDomain tenantDomain = AthenzDomain.from("my-tenant-domain");
        DeployState state = new DeployState.Builder().properties(
                new TestProperties()
                        .setAthenzDomain(tenantDomain)
                        .setHostedVespa(true))
                .build();
        createModel(root, state, null, clusterElem);
        Optional<AccessControl> maybeAccessControl =
                ((ApplicationContainer) root.getProducer("container/container.0")).getHttp().getAccessControl();
        assertThat(maybeAccessControl.isPresent(), is(true));
        AccessControl accessControl = maybeAccessControl.get();
        assertThat(accessControl.writeEnabled, is(false));
        assertThat(accessControl.readEnabled, is(false));
        assertThat(accessControl.domain, equalTo(tenantDomain.value()));
    }

    @Test
    public void access_control_is_implicitly_added_for_hosted_apps_with_existing_http_element() {
        Element clusterElem = DomBuilderTest.parse(
                "<container version='1.0'>",
                "  <http>",
                "    <server port='" + getDefaults().vespaWebServicePort() + "' id='main' />",
                "    <filtering>",
                "      <filter id='outer' />",
                "      <request-chain id='myChain'>",
                "        <filter id='inner' />",
                "      </request-chain>",
                "    </filtering>",
                "  </http>",
                nodesXml,
                "</container>" );
        AthenzDomain tenantDomain = AthenzDomain.from("my-tenant-domain");
        DeployState state = new DeployState.Builder().properties(
                new TestProperties()
                        .setAthenzDomain(tenantDomain)
                        .setHostedVespa(true))
                .build();
        createModel(root, state, null, clusterElem);
        Http http = ((ApplicationContainer) root.getProducer("container/container.0")).getHttp();
        assertThat(http.getAccessControl().isPresent(), is(true));
        assertThat(http.getFilterChains().hasChain(AccessControl.ACCESS_CONTROL_CHAIN_ID), is(true));
        assertThat(http.getFilterChains().hasChain(ComponentId.fromString("myChain")), is(true));
    }


    private String httpWithExcludedBinding(String excludedBinding) {
        return joinLines(
                "  <http>",
                "    <filtering>",
                "      <access-control domain='foo'>",
                "        <exclude>",
                "          <binding>" + excludedBinding + "</binding>",
                "        </exclude>",
                "      </access-control>",
                "    </filtering>",
                "  </http>");
    }

    private Http getHttp(Element clusterElem) {
        createModel(root, clusterElem);
        ContainerCluster cluster = (ContainerCluster) root.getChildren().get("container");
        Http http = cluster.getHttp();
        assertNotNull(http);
        return http;
    }

    private boolean containsBinding(Collection<FilterBinding> bindings, String binding) {
        for (FilterBinding b : bindings) {
            if (b.binding().contains(binding))
                return true;
        }
        return false;
    }
}
