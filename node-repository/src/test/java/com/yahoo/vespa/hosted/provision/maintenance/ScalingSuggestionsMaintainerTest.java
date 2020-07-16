// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterResources;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.Zone;
import com.yahoo.config.provisioning.FlavorsConfig;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.autoscale.NodeMetrics;
import com.yahoo.vespa.hosted.provision.autoscale.NodeMetricsDb;
import com.yahoo.vespa.hosted.provision.autoscale.Resource;
import com.yahoo.vespa.hosted.provision.provisioning.FlavorConfigBuilder;
import com.yahoo.vespa.hosted.provision.provisioning.ProvisioningTester;
import org.junit.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Tests the scaling suggestions maintainer integration.
 * The specific suggestions are not tested here.
 *
 * @author bratseth
 */
public class ScalingSuggestionsMaintainerTest {

    @Test
    public void testScalingSuggestionsMaintainer() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east3"))).flavorsConfig(flavorsConfig()).build();

        ApplicationId app1 = tester.makeApplicationId("app1");
        ClusterSpec cluster1 = tester.containerClusterSpec();

        ApplicationId app2 = tester.makeApplicationId("app2");
        ClusterSpec cluster2 = tester.contentClusterSpec();

        NodeMetricsDb nodeMetricsDb = new NodeMetricsDb();

        tester.makeReadyNodes(20, "flt", NodeType.host, 8);
        tester.deployZoneApp();

        tester.deploy(app1, cluster1, Capacity.from(new ClusterResources(5, 1, new NodeResources(4, 4, 10, 0.1)),
                                                    new ClusterResources(5, 1, new NodeResources(4, 4, 10, 0.1)),
                                                    false, true));
        tester.deploy(app2, cluster2, Capacity.from(new ClusterResources(5, 1, new NodeResources(4, 4, 10, 0.1)),
                                                    new ClusterResources(10, 1, new NodeResources(6.5, 5, 15, 0.1)),
                                                    false, true));

        addMeasurements(Resource.cpu,    0.9f, 500, app1, tester.nodeRepository(), nodeMetricsDb);
        addMeasurements(Resource.memory, 0.9f, 500, app1, tester.nodeRepository(), nodeMetricsDb);
        addMeasurements(Resource.disk,   0.9f, 500, app1, tester.nodeRepository(), nodeMetricsDb);
        addMeasurements(Resource.cpu,    0.99f, 500, app2, tester.nodeRepository(), nodeMetricsDb);
        addMeasurements(Resource.memory, 0.99f, 500, app2, tester.nodeRepository(), nodeMetricsDb);
        addMeasurements(Resource.disk,   0.99f, 500, app2, tester.nodeRepository(), nodeMetricsDb);

        ScalingSuggestionsMaintainer maintainer = new ScalingSuggestionsMaintainer(tester.nodeRepository(),
                                                                                   nodeMetricsDb,
                                                                                   Duration.ofMinutes(1),
                                                                                   new TestMetric());
        maintainer.maintain();

        assertEquals("14 nodes with [vcpu: 6.9, memory: 5.1 Gb, disk 15.0 Gb, bandwidth: 0.1 Gbps, storage type: remote]",
                     tester.nodeRepository().applications().get(app1).get().cluster(cluster1.id()).get().suggestedResources().get().toString());
        assertEquals("8 nodes with [vcpu: 14.7, memory: 4.0 Gb, disk 11.8 Gb, bandwidth: 0.1 Gbps, storage type: remote]",
                     tester.nodeRepository().applications().get(app2).get().cluster(cluster2.id()).get().suggestedResources().get().toString());
    }

    public void addMeasurements(Resource resource, float value, int count, ApplicationId applicationId,
                                NodeRepository nodeRepository, NodeMetricsDb db) {
        List<Node> nodes = nodeRepository.getNodes(applicationId, Node.State.active);
        for (int i = 0; i < count; i++) {
            for (Node node : nodes)
                db.add(List.of(new NodeMetrics.MetricValue(node.hostname(),
                                                           resource.metricName(),
                                                           nodeRepository.clock().instant().toEpochMilli(),
                                                          value * 100))); // the metrics are in %
        }
    }

    private FlavorsConfig flavorsConfig() {
        FlavorConfigBuilder b = new FlavorConfigBuilder();
        b.addFlavor("flt", 30, 30, 40, 3, Flavor.Type.BARE_METAL);
        b.addFlavor("cpu", 40, 20, 40, 3, Flavor.Type.BARE_METAL);
        b.addFlavor("mem", 20, 40, 40, 3, Flavor.Type.BARE_METAL);
        return b.build();
    }

}
