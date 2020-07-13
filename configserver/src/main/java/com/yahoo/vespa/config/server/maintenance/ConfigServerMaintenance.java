// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.maintenance;

import com.google.inject.Inject;
import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.component.AbstractComponent;
import com.yahoo.config.provision.SystemName;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.filedistribution.FileDistributionFactory;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.flags.FlagSource;

import java.time.Duration;

/**
 * Maintenance jobs of the config server.
 * Each maintenance job is a singleton instance of its implementing class, created and owned by this,
 * and running its own dedicated thread.
 *
 * @author hmusum
 */
public class ConfigServerMaintenance extends AbstractComponent {

    //private final TenantsMaintainer tenantsMaintainer;
    private final FileDistributionMaintainer fileDistributionMaintainer;
    private final SessionsMaintainer sessionsMaintainer;
    private final ApplicationPackageMaintainer applicationPackageMaintainer;

    @Inject
    public ConfigServerMaintenance(ConfigserverConfig configserverConfig,
                                   ApplicationRepository applicationRepository,
                                   Curator curator,
                                   FileDistributionFactory fileDistributionFactory,
                                   FlagSource flagSource) {
        DefaultTimes defaults = new DefaultTimes(configserverConfig);
        // TODO: Disabled until we have application metadata
        //tenantsMaintainer = new TenantsMaintainer(applicationRepository, curator, defaults.tenantsMaintainerInterval);
        fileDistributionMaintainer = new FileDistributionMaintainer(applicationRepository, curator, defaults.defaultInterval, configserverConfig, flagSource);
        sessionsMaintainer = new SessionsMaintainer(applicationRepository, curator, Duration.ofMinutes(1), flagSource);
        applicationPackageMaintainer = new ApplicationPackageMaintainer(applicationRepository, curator, Duration.ofMinutes(1), configserverConfig, flagSource);
    }

    @Override
    public void deconstruct() {
        //tenantsMaintainer.close();
        fileDistributionMaintainer.close();
        sessionsMaintainer.close();
        applicationPackageMaintainer.close();
    }

    /*
     * Default values from config. If one of the values needs to be changed, add the value to
     * configserver-config.xml in the config server application directory and restart the config server
     */
    private static class DefaultTimes {

        private final Duration defaultInterval;
        private final Duration tenantsMaintainerInterval;

        DefaultTimes(ConfigserverConfig configserverConfig) {
            this.defaultInterval = Duration.ofMinutes(configserverConfig.maintainerIntervalMinutes());
            boolean isCd = configserverConfig.system().equals(SystemName.cd.value());
            // TODO: Want job control or feature flag to control when to run this, for now use a very
            // long interval to avoid running the maintainer except in CD
            this.tenantsMaintainerInterval = isCd
                    ? defaultInterval
                    : Duration.ofMinutes(configserverConfig.tenantsMaintainerIntervalMinutes());
        }
    }

}
