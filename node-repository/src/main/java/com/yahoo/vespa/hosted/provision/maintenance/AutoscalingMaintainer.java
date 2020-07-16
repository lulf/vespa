// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterResources;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Deployer;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.applications.Application;
import com.yahoo.vespa.hosted.provision.applications.Applications;
import com.yahoo.vespa.hosted.provision.applications.Cluster;
import com.yahoo.vespa.hosted.provision.autoscale.Autoscaler;
import com.yahoo.vespa.hosted.provision.autoscale.NodeMetricsDb;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Maintainer making automatic scaling decisions
 *
 * @author bratseth
 */
public class AutoscalingMaintainer extends NodeRepositoryMaintainer {

    private final Autoscaler autoscaler;
    private final Deployer deployer;
    private final Metric metric;

    public AutoscalingMaintainer(NodeRepository nodeRepository,
                                 NodeMetricsDb metricsDb,
                                 Deployer deployer,
                                 Metric metric,
                                 Duration interval) {
        super(nodeRepository, interval, metric);
        this.autoscaler = new Autoscaler(metricsDb, nodeRepository);
        this.metric = metric;
        this.deployer = deployer;
    }

    @Override
    protected boolean maintain() {
        boolean success = true;
        if ( ! nodeRepository().zone().environment().isProduction()) return success;

        activeNodesByApplication().forEach((applicationId, nodes) -> autoscale(applicationId, nodes));
        return success;
    }

    private void autoscale(ApplicationId application, List<Node> applicationNodes) {
        try (MaintenanceDeployment deployment = new MaintenanceDeployment(application, deployer, metric, nodeRepository())) {
            if ( ! deployment.isValid()) return; // Another config server will consider this application
            nodesByCluster(applicationNodes).forEach((clusterId, clusterNodes) -> autoscale(application, clusterId, clusterNodes, deployment));
        }
    }

    private void autoscale(ApplicationId applicationId,
                           ClusterSpec.Id clusterId,
                           List<Node> clusterNodes,
                           MaintenanceDeployment deployment) {
        Application application = nodeRepository().applications().get(applicationId).orElse(new Application(applicationId));
        Optional<Cluster> cluster = application.cluster(clusterId);
        if (cluster.isEmpty()) return;
        Optional<ClusterResources> target = autoscaler.autoscale(cluster.get(), clusterNodes);
        if ( ! cluster.get().targetResources().equals(target))
            applications().put(application.with(cluster.get().withTarget(target)), deployment.applicationLock().get());
        if (target.isPresent()) {
            logAutoscaling(target.get(), applicationId, clusterId, clusterNodes);
            deployment.activate();
        }
    }

    private Applications applications() {
        return nodeRepository().applications();
    }

    private void logAutoscaling(ClusterResources target,
                                ApplicationId application,
                                ClusterSpec.Id clusterId,
                                List<Node> clusterNodes) {
        int currentGroups = (int)clusterNodes.stream().map(node -> node.allocation().get().membership().cluster().group()).distinct().count();
        ClusterSpec.Type clusterType = clusterNodes.get(0).allocation().get().membership().cluster().type();
        log.info("Autoscaling " + application + " " + clusterType + " " + clusterId + ":" +
                 "\nfrom " + toString(clusterNodes.size(), currentGroups, clusterNodes.get(0).resources()) +
                 "\nto   " + toString(target.nodes(), target.groups(), target.nodeResources()));
    }

    private String toString(int nodes, int groups, NodeResources resources) {
        return String.format(nodes + (groups > 1 ? " (in " + groups + " groups)" : "") +
                             " * [vcpu: %0$.1f, memory: %1$.1f Gb, disk %2$.1f Gb]" +
                             " (total: [vcpu: %3$.1f, memory: %4$.1f Gb, disk: %5$.1f Gb])",
                             resources.vcpu(), resources.memoryGb(), resources.diskGb(),
                             nodes * resources.vcpu(), nodes * resources.memoryGb(), nodes * resources.diskGb());
    }

    private Map<ClusterSpec.Id, List<Node>> nodesByCluster(List<Node> applicationNodes) {
        return applicationNodes.stream().collect(Collectors.groupingBy(n -> n.allocation().get().membership().cluster().id()));
    }

}
