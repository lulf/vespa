// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.DockerImage;
import com.yahoo.config.provision.NodeFlavors;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.Zone;
import com.yahoo.jdisc.Metric;
import com.yahoo.test.ManualClock;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.vespa.applicationmodel.ApplicationInstance;
import com.yahoo.vespa.applicationmodel.ApplicationInstanceReference;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.curator.mock.MockCurator;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import com.yahoo.vespa.hosted.provision.LockedNodeList;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.Generation;
import com.yahoo.vespa.hosted.provision.node.IP;
import com.yahoo.vespa.hosted.provision.provisioning.EmptyProvisionServiceProvider;
import com.yahoo.vespa.hosted.provision.provisioning.FlavorConfigBuilder;
import com.yahoo.vespa.hosted.provision.testutils.MockNameResolver;
import com.yahoo.vespa.orchestrator.Orchestrator;
import com.yahoo.vespa.orchestrator.status.HostInfo;
import com.yahoo.vespa.orchestrator.status.HostStatus;
import com.yahoo.vespa.service.monitor.ServiceModel;
import com.yahoo.vespa.service.monitor.ServiceMonitor;
import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author oyving
 * @author smorgrav
 */
public class MetricsReporterTest {

    private final ServiceMonitor serviceMonitor = mock(ServiceMonitor.class);
    private final ApplicationInstanceReference reference = mock(ApplicationInstanceReference.class);

    @Before
    public void setUp() {
        // On the serviceModel returned by serviceMonitor.getServiceModelSnapshot(),
        // 2 methods should be used by MetricsReporter:
        //  - getServiceInstancesByHostName() -> empty Map
        //  - getApplication() which is mapped to a dummy ApplicationInstanceReference and
        //    used for lookup.
        ServiceModel serviceModel = mock(ServiceModel.class);
        when(serviceMonitor.getServiceModelSnapshot()).thenReturn(serviceModel);
        when(serviceModel.getServiceInstancesByHostName()).thenReturn(Map.of());
        ApplicationInstance applicationInstance = mock(ApplicationInstance.class);
        when(serviceModel.getApplication(any())).thenReturn(Optional.of(applicationInstance));
        when(applicationInstance.reference()).thenReturn(reference);
    }

    @Test
    public void test_registered_metric() {
        NodeFlavors nodeFlavors = FlavorConfigBuilder.createDummies("default");
        Curator curator = new MockCurator();
        NodeRepository nodeRepository = new NodeRepository(nodeFlavors,
                                                           new EmptyProvisionServiceProvider().getHostResourcesCalculator(),
                                                           curator,
                                                           Clock.systemUTC(),
                                                           Zone.defaultZone(),
                                                           new MockNameResolver().mockAnyLookup(),
                                                           DockerImage.fromString("docker-registry.domain.tld:8080/dist/vespa"),
                                                           new InMemoryFlagSource(),
                                                           true,
                                                           false,
                                                           0);
        Node node = nodeRepository.createNode("openStackId", "hostname", Optional.empty(), nodeFlavors.getFlavorOrThrow("default"), NodeType.tenant);
        nodeRepository.addNodes(List.of(node), Agent.system);
        Node hostNode = nodeRepository.createNode("openStackId2", "parent", Optional.empty(), nodeFlavors.getFlavorOrThrow("default"), NodeType.proxy);
        nodeRepository.addNodes(List.of(hostNode), Agent.system);

        Map<String, Number> expectedMetrics = new HashMap<>();
        expectedMetrics.put("hostedVespa.provisionedHosts", 1);
        expectedMetrics.put("hostedVespa.parkedHosts", 0);
        expectedMetrics.put("hostedVespa.readyHosts", 0);
        expectedMetrics.put("hostedVespa.reservedHosts", 0);
        expectedMetrics.put("hostedVespa.activeHosts", 0);
        expectedMetrics.put("hostedVespa.inactiveHosts", 0);
        expectedMetrics.put("hostedVespa.dirtyHosts", 0);
        expectedMetrics.put("hostedVespa.failedHosts", 0);
        expectedMetrics.put("hostedVespa.deprovisionedHosts", 0);
        expectedMetrics.put("hostedVespa.pendingRedeployments", 42);
        expectedMetrics.put("hostedVespa.docker.totalCapacityDisk", 0.0);
        expectedMetrics.put("hostedVespa.docker.totalCapacityMem", 0.0);
        expectedMetrics.put("hostedVespa.docker.totalCapacityCpu", 0.0);
        expectedMetrics.put("hostedVespa.docker.freeCapacityDisk", 0.0);
        expectedMetrics.put("hostedVespa.docker.freeCapacityMem", 0.0);
        expectedMetrics.put("hostedVespa.docker.freeCapacityCpu", 0.0);

        expectedMetrics.put("wantedRebootGeneration", 0L);
        expectedMetrics.put("currentRebootGeneration", 0L);
        expectedMetrics.put("wantToReboot", 0);
        expectedMetrics.put("wantToRetire", 0);
        expectedMetrics.put("wantToDeprovision", 0);
        expectedMetrics.put("failReport", 0);
        expectedMetrics.put("allowedToBeDown", 1);
        expectedMetrics.put("suspended", 1);
        expectedMetrics.put("suspendedSeconds", 123L);
        expectedMetrics.put("numberOfServices", 0L);

        ManualClock clock = new ManualClock(Instant.ofEpochSecond(124));

        Orchestrator orchestrator = mock(Orchestrator.class);
        when(orchestrator.getHostInfo(eq(reference), any())).thenReturn(
                HostInfo.createSuspended(HostStatus.ALLOWED_TO_BE_DOWN, Instant.ofEpochSecond(1)));

        TestMetric metric = new TestMetric();
        MetricsReporter metricsReporter = new MetricsReporter(
                nodeRepository,
                metric,
                orchestrator,
                serviceMonitor,
                () -> 42,
                Duration.ofMinutes(1),
                clock);
        metricsReporter.maintain();

        assertEquals(expectedMetrics, metric.values);
    }

    @Test
    public void docker_metrics() {
        NodeFlavors nodeFlavors = FlavorConfigBuilder.createDummies("host", "docker", "docker2");
        Curator curator = new MockCurator();
        NodeRepository nodeRepository = new NodeRepository(nodeFlavors,
                                                           new EmptyProvisionServiceProvider().getHostResourcesCalculator(),
                                                           curator,
                                                           Clock.systemUTC(),
                                                           Zone.defaultZone(),
                                                           new MockNameResolver().mockAnyLookup(),
                                                           DockerImage.fromString("docker-registry.domain.tld:8080/dist/vespa"),
                                                           new InMemoryFlagSource(),
                                                           true,
                                                           false,
                                                           0);

        // Allow 4 containers
        Set<String> ipAddressPool = Set.of("::2", "::3", "::4", "::5");

        Node dockerHost = Node.create("openStackId1", new IP.Config(Set.of("::1"), ipAddressPool), "dockerHost",
                                      Optional.empty(), Optional.empty(), nodeFlavors.getFlavorOrThrow("host"), Optional.empty(), NodeType.host);
        nodeRepository.addNodes(List.of(dockerHost), Agent.system);
        nodeRepository.dirtyRecursively("dockerHost", Agent.system, getClass().getSimpleName());
        nodeRepository.setReady("dockerHost", Agent.system, getClass().getSimpleName());

        Node container1 = Node.createDockerNode(Set.of("::2"), "container1",
                                                "dockerHost", new NodeResources(1, 3, 2, 1), NodeType.tenant);
        container1 = container1.with(allocation(Optional.of("app1"), container1).get());
        nodeRepository.addDockerNodes(new LockedNodeList(List.of(container1), nodeRepository.lockUnallocated()));

        Node container2 = Node.createDockerNode(Set.of("::3"), "container2",
                                                "dockerHost", new NodeResources(2, 4, 4, 1), NodeType.tenant);
        container2 = container2.with(allocation(Optional.of("app2"), container2).get());
        nodeRepository.addDockerNodes(new LockedNodeList(List.of(container2), nodeRepository.lockUnallocated()));

        NestedTransaction transaction = new NestedTransaction();
        nodeRepository.activate(nodeRepository.getNodes(NodeType.host), transaction);
        transaction.commit();

        Orchestrator orchestrator = mock(Orchestrator.class);
        when(orchestrator.getHostInfo(eq(reference), any())).thenReturn(HostInfo.createNoRemarks());

        TestMetric metric = new TestMetric();
        ManualClock clock = new ManualClock();
        MetricsReporter metricsReporter = new MetricsReporter(
                nodeRepository,
                metric,
                orchestrator,
                serviceMonitor,
                () -> 42,
                Duration.ofMinutes(1),
                clock);
        metricsReporter.maintain();

        assertEquals(0, metric.values.get("hostedVespa.readyHosts")); // Only tenants counts
        assertEquals(2, metric.values.get("hostedVespa.reservedHosts"));

        assertEquals(120.0, metric.values.get("hostedVespa.docker.totalCapacityDisk"));
        assertEquals(100.0, metric.values.get("hostedVespa.docker.totalCapacityMem"));
        assertEquals(  7.0, metric.values.get("hostedVespa.docker.totalCapacityCpu"));

        assertEquals(114.0, metric.values.get("hostedVespa.docker.freeCapacityDisk"));
        assertEquals( 93.0, metric.values.get("hostedVespa.docker.freeCapacityMem"));
        assertEquals(  4.0, metric.values.get("hostedVespa.docker.freeCapacityCpu"));

        Metric.Context app1context = metric.createContext(Map.of("app", "test.default", "tenantName", "app1", "applicationId", "app1.test.default"));
        assertEquals(2.0, metric.sumDoubleValues("hostedVespa.docker.allocatedCapacityDisk", app1context), 0.01d);
        assertEquals(3.0, metric.sumDoubleValues("hostedVespa.docker.allocatedCapacityMem", app1context), 0.01d);
        assertEquals(1.0, metric.sumDoubleValues("hostedVespa.docker.allocatedCapacityCpu", app1context), 0.01d);

        Metric.Context app2context = metric.createContext(Map.of("app", "test.default", "tenantName", "app2", "applicationId", "app2.test.default"));
        assertEquals(4.0, metric.sumDoubleValues("hostedVespa.docker.allocatedCapacityDisk", app2context), 0.01d);
        assertEquals(4.0, metric.sumDoubleValues("hostedVespa.docker.allocatedCapacityMem", app2context), 0.01d);
        assertEquals(2.0, metric.sumDoubleValues("hostedVespa.docker.allocatedCapacityCpu", app2context), 0.01d);
    }

    private ApplicationId app(String tenant) {
        return new ApplicationId.Builder()
                .tenant(tenant)
                .applicationName("test")
                .instanceName("default").build();
    }

    private Optional<Allocation> allocation(Optional<String> tenant, Node owner) {
        if (tenant.isPresent()) {
            Allocation allocation = new Allocation(app(tenant.get()),
                                                   ClusterMembership.from("container/id1/0/3", new Version(), Optional.empty()),
                                                   owner.resources(),
                                                   Generation.initial(),
                                                   false);
            return Optional.of(allocation);
        }
        return Optional.empty();
    }

}
