package com.yahoo.vespa.config.server.maintenance;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.config.FileReference;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.session.RemoteSession;
import com.yahoo.vespa.config.server.session.SessionRepository;
import com.yahoo.vespa.config.server.tenant.Tenant;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.filedistribution.FileDownloader;
import com.yahoo.vespa.flags.BooleanFlag;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.flags.Flags;

import java.io.File;
import java.time.Duration;
import java.util.Set;
import java.util.logging.Logger;

import static com.yahoo.vespa.config.server.filedistribution.FileDistributionUtil.getFileReferencesOnDisk;
import static com.yahoo.vespa.config.server.filedistribution.FileDistributionUtil.createConnectionPool;

/**
 * Verifies that all active sessions has an application package on local disk.
 * If not, the package is downloaded with file distribution. This can happen e.g.
 * if a configserver is down when the application is deployed.
 *
 * @author gjoranv
 */
public class ApplicationPackageMaintainer extends ConfigServerMaintainer {
    private static final Logger log = Logger.getLogger(ApplicationPackageMaintainer.class.getName());

    private final ApplicationRepository applicationRepository;
    private final ConfigserverConfig configserverConfig;
    private final File downloadDirectory;
    private final BooleanFlag distributeApplicationPackage;

    ApplicationPackageMaintainer(ApplicationRepository applicationRepository,
                                 Curator curator,
                                 Duration interval,
                                 ConfigserverConfig configserverConfig,
                                 FlagSource flagSource) {
        super(applicationRepository, curator, flagSource, interval, interval);
        this.applicationRepository = applicationRepository;
        this.configserverConfig = configserverConfig;

        distributeApplicationPackage = Flags.CONFIGSERVER_DISTRIBUTE_APPLICATION_PACKAGE.bindTo(flagSource);
        downloadDirectory = new File(Defaults.getDefaults().underVespaHome(configserverConfig.fileReferencesDir()));
    }

    @Override
    protected void maintain() {
        if (! distributeApplicationPackage.value()) return;

        try (var fileDownloader = new FileDownloader(createConnectionPool(configserverConfig), downloadDirectory)) {
            for (var applicationId : applicationRepository.listApplications()) {
                log.fine(() -> "Verifying application package for " + applicationId);
                RemoteSession session = applicationRepository.getActiveSession(applicationId);
                if (session == null) continue;  // App might be deleted after call to listApplications()

                FileReference applicationPackage = session.getApplicationPackageReference();
                long sessionId = session.getSessionId();
                log.fine(() -> "Verifying application package file reference " + applicationPackage + " for session " + sessionId);

                if (applicationPackage != null) {
                    if (missingOnDisk(applicationPackage)) {
                        log.fine(() -> "Downloading missing application package for application " + applicationId + " - session " + sessionId);

                        if (fileDownloader.getFile(applicationPackage).isEmpty()) {
                            log.warning("Failed to download application package for application " + applicationId + " - session " + sessionId);
                            continue;
                        }
                    }
                    createLocalSessionIfMissing(applicationId, sessionId);
                }
            }
        }
    }

    private void createLocalSessionIfMissing(ApplicationId applicationId, long sessionId) {
        Tenant tenant = applicationRepository.tenantRepository().getTenant(applicationId.tenant());
        SessionRepository sessionRepository = tenant.getSessionRepository();
        if (sessionRepository.getLocalSession(sessionId) == null)
            sessionRepository.createLocalSessionUsingDistributedApplicationPackage(sessionId);
    }

    private boolean missingOnDisk(FileReference applicationPackageReference) {
        Set<String> fileReferencesOnDisk = getFileReferencesOnDisk(downloadDirectory);
        return ! fileReferencesOnDisk.contains(applicationPackageReference.value());
    }

}
