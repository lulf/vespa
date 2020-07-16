// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.maintenance;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.flags.FlagSource;

import java.io.File;
import java.time.Duration;

/**
 * Removes unused file references from disk
 * <p>
 * Note: Unit test is in ApplicationRepositoryTest
 *
 * @author hmusum
 */
public class FileDistributionMaintainer extends ConfigServerMaintainer {

    private final ApplicationRepository applicationRepository;
    private final File fileReferencesDir;
    private final Duration maxUnusedFileReferenceAge;

    FileDistributionMaintainer(ApplicationRepository applicationRepository,
                               Curator curator,
                               Duration interval,
                               ConfigserverConfig configserverConfig,
                               FlagSource flagSource) {
        super(applicationRepository, curator, flagSource, interval, interval);
        this.applicationRepository = applicationRepository;
        this.maxUnusedFileReferenceAge = Duration.ofHours(configserverConfig.keepUnusedFileReferencesHours());
        this.fileReferencesDir = new File(Defaults.getDefaults().underVespaHome(configserverConfig.fileReferencesDir()));
    }

    @Override
    protected boolean maintain() {
        applicationRepository.deleteUnusedFiledistributionReferences(fileReferencesDir, maxUnusedFileReferenceAge);
        return true;
    }
}
