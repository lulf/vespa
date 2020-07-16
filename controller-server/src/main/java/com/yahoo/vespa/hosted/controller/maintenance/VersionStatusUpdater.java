// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.organization.SystemMonitor;
import com.yahoo.vespa.hosted.controller.versions.VersionStatus;
import com.yahoo.vespa.hosted.controller.versions.VespaVersion;
import com.yahoo.yolean.Exceptions;

import java.time.Duration;
import java.util.logging.Level;

import static com.yahoo.vespa.hosted.controller.api.integration.organization.SystemMonitor.Confidence.broken;
import static com.yahoo.vespa.hosted.controller.api.integration.organization.SystemMonitor.Confidence.high;
import static com.yahoo.vespa.hosted.controller.api.integration.organization.SystemMonitor.Confidence.low;
import static com.yahoo.vespa.hosted.controller.api.integration.organization.SystemMonitor.Confidence.normal;

/**
 * This maintenance job periodically updates the version status.
 * Since the version status is expensive to compute and does not need to be perfectly up to date,
 * we do not want to recompute it each time it is accessed.
 * 
 * @author bratseth
 */
public class VersionStatusUpdater extends ControllerMaintainer {

    public VersionStatusUpdater(Controller controller, Duration interval) {
        super(controller, interval);
    }

    @Override
    protected boolean maintain() {
        try {
            VersionStatus newStatus = VersionStatus.compute(controller());
            controller().updateVersionStatus(newStatus);
            newStatus.systemVersion().ifPresent(version -> {
                controller().serviceRegistry().systemMonitor().reportSystemVersion(version.versionNumber(),
                                                                                   convert(version.confidence()));
            });
            return true;
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to compute version status: " + Exceptions.toMessageString(e) +
                                   ". Retrying in " + interval());
        }
        return false;
    }

    static SystemMonitor.Confidence convert(VespaVersion.Confidence confidence) {
        switch (confidence) {
            case broken: return broken;
            case low:    return low;
            case normal: return normal;
            case high:   return high;
            default: throw new IllegalArgumentException("Unexpected confidence '" + confidence + "'");
        }
    }

}
