// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.autoscale.NodeMetrics;
import com.yahoo.vespa.hosted.provision.autoscale.NodeMetricsDb;
import com.yahoo.yolean.Exceptions;

import java.time.Duration;
import java.util.logging.Level;

/**
 * Maintainer which keeps the node metric db up to date by periodically fetching metrics from all
 * active nodes.
 *
 * @author bratseth
 */
public class NodeMetricsDbMaintainer extends NodeRepositoryMaintainer {

    private static final int maxWarningsPerInvocation = 2;

    private final NodeMetrics nodeMetrics;
    private final NodeMetricsDb nodeMetricsDb;

    public NodeMetricsDbMaintainer(NodeRepository nodeRepository,
                                   NodeMetrics nodeMetrics,
                                   NodeMetricsDb nodeMetricsDb,
                                   Duration interval,
                                   Metric metric) {
        super(nodeRepository, interval, metric);
        this.nodeMetrics = nodeMetrics;
        this.nodeMetricsDb = nodeMetricsDb;
    }

    @Override
    protected boolean maintain() {
        int warnings = 0;
        for (ApplicationId application : activeNodesByApplication().keySet()) {
            try {
                nodeMetricsDb.add(nodeMetrics.fetchMetrics(application));
            }
            catch (Exception e) {
                // TODO: Don't warn if this only happens occasionally
                if (warnings++ < maxWarningsPerInvocation)
                    log.log(Level.WARNING, "Could not update metrics for " + application + ": " + Exceptions.toMessageString(e));
            }
        }
        nodeMetricsDb.gc(nodeRepository().clock());
        return warnings == 0;
    }

}
