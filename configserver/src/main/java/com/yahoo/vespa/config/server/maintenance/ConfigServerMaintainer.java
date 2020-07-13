// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.maintenance;

import com.yahoo.concurrent.maintenance.JobControl;
import com.yahoo.concurrent.maintenance.JobControlState;
import com.yahoo.concurrent.maintenance.Maintainer;
import com.yahoo.path.Path;
import com.yahoo.transaction.Mutex;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.flags.ListFlag;

import java.time.Duration;
import java.util.Set;

/**
 * A maintainer is some job which runs at a fixed interval to perform some maintenance task in the config server.
 *
 * @author hmusum
 */
public abstract class ConfigServerMaintainer extends Maintainer {

    protected final ApplicationRepository applicationRepository;

    ConfigServerMaintainer(ApplicationRepository applicationRepository, Curator curator, FlagSource flagSource,
                           Duration initialDelay, Duration interval) {
        super(null, interval, initialDelay, new JobControl(new JobControlFlags(curator, flagSource)));
        this.applicationRepository = applicationRepository;
    }

    private static class JobControlFlags implements JobControlState {

        private static final Path root = Path.fromString("/configserver/v1/");
        private static final Path lockRoot = root.append("locks");

        private final Curator curator;
        private final ListFlag<String> inactiveJobsFlag;

        public JobControlFlags(Curator curator, FlagSource flagSource) {
            this.curator = curator;
            this.inactiveJobsFlag = Flags.INACTIVE_MAINTENANCE_JOBS.bindTo(flagSource);
        }

        @Override
        public Set<String> readInactiveJobs() {
            return Set.copyOf(inactiveJobsFlag.value());
        }

        @Override
        public Mutex lockMaintenanceJob(String job) {
            return curator.lock(lockRoot.append(job), Duration.ofSeconds(1));
        }

    }

}
