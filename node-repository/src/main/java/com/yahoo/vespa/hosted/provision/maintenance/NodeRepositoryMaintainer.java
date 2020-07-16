// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.concurrent.maintenance.JobMetrics;
import com.yahoo.concurrent.maintenance.Maintainer;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.NodeType;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A maintainer is some job which runs at a fixed rate to perform some maintenance task on the node repo.
 *
 * @author bratseth
 */
public abstract class NodeRepositoryMaintainer extends Maintainer {

    private final NodeRepository nodeRepository;

    public NodeRepositoryMaintainer(NodeRepository nodeRepository, Duration interval, Metric metric) {
        super(null, interval, nodeRepository.clock().instant(), nodeRepository.jobControl(),
              jobMetrics(nodeRepository.clock(), metric), nodeRepository.database().cluster());
        this.nodeRepository = nodeRepository;
    }

    protected static Duration min(Duration a, Duration b) {
        return a.toMillis() < b.toMillis() ? a : b;
    }

    /** Returns the node repository */
    protected NodeRepository nodeRepository() { return nodeRepository; }

    /** A utility to group active tenant nodes by application */
    protected Map<ApplicationId, List<Node>> activeNodesByApplication() {
        return nodeRepository().list().nodeType(NodeType.tenant).state(Node.State.active).asList()
                               .stream()
                               .filter(node -> ! node.allocation().get().owner().instance().isTester())
                               .collect(Collectors.groupingBy(node -> node.allocation().get().owner()));
    }

    private static JobMetrics jobMetrics(Clock clock, Metric metric) {
        return new JobMetrics(clock, (job, instant) -> {
            Duration sinceSuccess = Duration.between(instant, clock.instant());
            metric.set("maintenance.secondsSinceSuccess", sinceSuccess.getSeconds(), metric.createContext(Map.of("job", job)));
        });
    }

}
