// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.application;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.component.Version;
import com.yahoo.concurrent.StripedExecutor;
import com.yahoo.config.FileReference;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.TenantName;
import com.yahoo.path.Path;
import com.yahoo.text.Utf8;
import com.yahoo.transaction.Transaction;
import com.yahoo.vespa.config.ConfigKey;
import com.yahoo.vespa.config.GetConfigRequest;
import com.yahoo.vespa.config.protocol.ConfigResponse;
import com.yahoo.vespa.config.server.GlobalComponentRegistry;
import com.yahoo.vespa.config.server.NotFoundException;
import com.yahoo.vespa.config.server.ReloadListener;
import com.yahoo.vespa.config.server.RequestHandler;
import com.yahoo.vespa.config.server.deploy.TenantFileSystemDirs;
import com.yahoo.vespa.config.server.host.HostRegistry;
import com.yahoo.vespa.config.server.host.HostValidator;
import com.yahoo.vespa.config.server.monitoring.MetricUpdater;
import com.yahoo.vespa.config.server.monitoring.Metrics;
import com.yahoo.vespa.config.server.rpc.ConfigResponseFactory;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.curator.transaction.CuratorOperations;
import com.yahoo.vespa.curator.transaction.CuratorTransaction;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toSet;

/**
 * The applications of a tenant, backed by ZooKeeper.
 *
 * Each application is stored under /config/v2/tenants/&lt;tenant&gt;/applications/&lt;application&gt;,
 * the root contains the currently active session, if any. Locks for synchronising writes to these paths, and changes
 * to the config of this application, are found under /config/v2/tenants/&lt;tenant&gt;/locks/&lt;application&gt;.
 *
 * @author Ulf Lilleengen
 * @author jonmv
 */
public class TenantApplications implements RequestHandler, HostValidator<ApplicationId> {

    private static final Logger log = Logger.getLogger(TenantApplications.class.getName());

    private final Curator curator;
    private final Path applicationsPath;
    private final Path locksPath;
    private final Curator.DirectoryCache directoryCache;
    private final Executor zkWatcherExecutor;
    private final Metrics metrics;
    private final TenantName tenant;
    private final ReloadListener reloadListener;
    private final ConfigResponseFactory responseFactory;
    private final HostRegistry<ApplicationId> hostRegistry;
    private final ApplicationMapper applicationMapper = new ApplicationMapper();
    private final MetricUpdater tenantMetricUpdater;
    private final Clock clock = Clock.systemUTC();
    private final TenantFileSystemDirs tenantFileSystemDirs;

    public TenantApplications(TenantName tenant, Curator curator, StripedExecutor<TenantName> zkWatcherExecutor,
                              ExecutorService zkCacheExecutor, Metrics metrics, ReloadListener reloadListener,
                              ConfigserverConfig configserverConfig, HostRegistry<ApplicationId> hostRegistry,
                              TenantFileSystemDirs tenantFileSystemDirs) {
        this.curator = curator;
        this.applicationsPath = TenantRepository.getApplicationsPath(tenant);
        this.locksPath = TenantRepository.getLocksPath(tenant);
        this.tenant = tenant;
        this.zkWatcherExecutor = command -> zkWatcherExecutor.execute(tenant, command);
        this.directoryCache = curator.createDirectoryCache(applicationsPath.getAbsolute(), false, false, zkCacheExecutor);
        this.directoryCache.addListener(this::childEvent);
        this.directoryCache.start();
        this.metrics = metrics;
        this.reloadListener = reloadListener;
        this.responseFactory = ConfigResponseFactory.create(configserverConfig);
        this.tenantMetricUpdater = metrics.getOrCreateMetricUpdater(Metrics.createDimensions(tenant));
        this.hostRegistry = hostRegistry;
        this.tenantFileSystemDirs = tenantFileSystemDirs;
    }

    // For testing only
    public static TenantApplications create(GlobalComponentRegistry componentRegistry, TenantName tenantName) {
        return new TenantApplications(tenantName,
                                      componentRegistry.getCurator(),
                                      componentRegistry.getZkWatcherExecutor(),
                                      componentRegistry.getZkCacheExecutor(),
                                      componentRegistry.getMetrics(),
                                      componentRegistry.getReloadListener(),
                                      componentRegistry.getConfigserverConfig(),
                                      componentRegistry.getHostRegistries().createApplicationHostRegistry(tenantName),
                                      new TenantFileSystemDirs(componentRegistry.getConfigServerDB(), tenantName));
    }

    /**
     * List the active applications of a tenant in this config server.
     *
     * @return a list of {@link ApplicationId}s that are active.
     */
    public List<ApplicationId> activeApplications() {
        return curator.getChildren(applicationsPath).stream()
                      .sorted()
                      .map(ApplicationId::fromSerializedForm)
                      .filter(id -> activeSessionOf(id).isPresent())
                      .collect(Collectors.toUnmodifiableList());
    }

    public boolean exists(ApplicationId id) {
        return curator.exists(applicationPath(id));
    }

    /** Returns the active session id for the given application. Returns Optional.empty if application not found or no active session exists. */
    public Optional<Long> activeSessionOf(ApplicationId id) {
        Optional<byte[]> data = curator.getData(applicationPath(id));
        return (data.isEmpty() || data.get().length == 0)
                ? Optional.empty()
                : data.map(bytes -> Long.parseLong(Utf8.toString(bytes)));
    }

    public boolean hasLocalSession(long sessionId) {
        return Files.exists(Paths.get(tenantFileSystemDirs.sessionsPath().getAbsolutePath(), String.valueOf(sessionId)));
    }

    /**
     * Returns a transaction which writes the given session id as the currently active for the given application.
     *
     * @param applicationId An {@link ApplicationId} that represents an active application.
     * @param sessionId Id of the session containing the application package for this id.
     */
    public Transaction createPutTransaction(ApplicationId applicationId, long sessionId) {
        return new CuratorTransaction(curator).add(CuratorOperations.setData(applicationPath(applicationId).getAbsolute(), Utf8.toAsciiBytes(sessionId)));
    }

    /**
     * Creates a node for the given application, marking its existence.
     */
    public void createApplication(ApplicationId id) {
        try (Lock lock = lock(id)) {
            curator.create(applicationPath(id));
        }
    }

    /**
     * Return the active session id for a given application.
     *
     * @param  applicationId an {@link ApplicationId}
     * @return session id of given application id.
     * @throws IllegalArgumentException if the application does not exist
     */
    public long requireActiveSessionOf(ApplicationId applicationId) {
        return activeSessionOf(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application '" + applicationId + "' has no active session."));
    }

    /**
     * Returns a transaction which deletes this application.
     */
    public CuratorTransaction createDeleteTransaction(ApplicationId applicationId) {
        return CuratorTransaction.from(CuratorOperations.deleteAll(applicationPath(applicationId).getAbsolute(), curator), curator);
    }

    /**
     * Removes all applications not known to this from the config server state.
     */
    public void removeUnusedApplications() {
        removeApplicationsExcept(Set.copyOf(activeApplications()));
    }

    /**
     * Closes the application repo. Once a repo has been closed, it should not be used again.
     */
    public void close() {
        directoryCache.close();
    }

    /** Returns the lock for changing the session status of the given application. */
    public Lock lock(ApplicationId id) {
        return curator.lock(lockPath(id), Duration.ofMinutes(1)); // These locks shouldn't be held for very long.
    }

    private void childEvent(CuratorFramework client, PathChildrenCacheEvent event) {
        zkWatcherExecutor.execute(() -> {
            switch (event.getType()) {
                case CHILD_ADDED:
                    applicationAdded(ApplicationId.fromSerializedForm(Path.fromString(event.getData().getPath()).getName()));
                    break;
                // Event CHILD_REMOVED will be triggered on all config servers if deleteApplication() above is called on one of them
                case CHILD_REMOVED:
                    applicationRemoved(ApplicationId.fromSerializedForm(Path.fromString(event.getData().getPath()).getName()));
                    break;
                case CHILD_UPDATED:
                    // do nothing, application just got redeployed
                    break;
                default:
                    break;
            }
            // We may have lost events and may need to remove applications.
            // New applications are added when session is added, not here. See SessionRepository.
            removeUnusedApplications();
        });
    }

    private void applicationRemoved(ApplicationId applicationId) {
        removeApplication(applicationId);
        log.log(Level.INFO, TenantRepository.logPre(applicationId) + "Application removed: " + applicationId);
    }

    private void applicationAdded(ApplicationId applicationId) {
        log.log(Level.FINE, TenantRepository.logPre(applicationId) + "Application added: " + applicationId);
    }

    private Path applicationPath(ApplicationId id) {
        return applicationsPath.append(id.serializedForm());
    }

    private Path lockPath(ApplicationId id) {
        return locksPath.append(id.serializedForm());
    }

    /**
     * Gets a config for the given app, or null if not found
     */
    @Override
    public ConfigResponse resolveConfig(ApplicationId appId, GetConfigRequest req, Optional<Version> vespaVersion) {
        Application application = getApplication(appId, vespaVersion);
        if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, TenantRepository.logPre(appId) + "Resolving for tenant '" + tenant + "' with handler for application '" + application + "'");
        }
        return application.resolveConfig(req, responseFactory);
    }

    private void notifyReloadListeners(ApplicationSet applicationSet) {
        reloadListener.hostsUpdated(tenant, hostRegistry.getAllHosts());
        reloadListener.configActivated(applicationSet);
    }

    /**
     * Activates the config of the given app. Notifies listeners
     *
     * @param applicationSet the {@link ApplicationSet} to be reloaded
     */
    public void reloadConfig(ApplicationSet applicationSet) {
        ApplicationId id = applicationSet.getId();
        try (Lock lock = lock(id)) {
            if ( ! exists(id))
                return; // Application was deleted before activation.
            if (applicationSet.getApplicationGeneration() != requireActiveSessionOf(id))
                return; // Application activated a new session before we got here.

            setLiveApp(applicationSet);
            notifyReloadListeners(applicationSet);
        }
    }

    public void removeApplication(ApplicationId applicationId) {
        try (Lock lock = lock(applicationId)) {
            if (exists(applicationId))
                return; // Application was deployed again.

            if (applicationMapper.hasApplication(applicationId, clock.instant())) {
                applicationMapper.remove(applicationId);
                hostRegistry.removeHostsForKey(applicationId);
                reloadListenersOnRemove(applicationId);
                tenantMetricUpdater.setApplications(applicationMapper.numApplications());
                metrics.removeMetricUpdater(Metrics.createDimensions(applicationId));
            }
        }
    }

    public void removeApplicationsExcept(Set<ApplicationId> applications) {
        for (ApplicationId activeApplication : applicationMapper.listApplicationIds()) {
            if ( ! applications.contains(activeApplication)) {
                log.log(Level.INFO, "Will remove deleted application " + activeApplication.toShortString());
                removeApplication(activeApplication);
            }
        }
    }

    private void reloadListenersOnRemove(ApplicationId applicationId) {
        reloadListener.hostsUpdated(tenant, hostRegistry.getAllHosts());
        reloadListener.applicationRemoved(applicationId);
    }

    private void setLiveApp(ApplicationSet applicationSet) {
        ApplicationId id = applicationSet.getId();
        Collection<String> hostsForApp = applicationSet.getAllHosts();
        hostRegistry.update(id, hostsForApp);
        applicationSet.updateHostMetrics();
        tenantMetricUpdater.setApplications(applicationMapper.numApplications());
        applicationMapper.register(id, applicationSet);
    }

    @Override
    public Set<ConfigKey<?>> listNamedConfigs(ApplicationId appId, Optional<Version> vespaVersion, ConfigKey<?> keyToMatch, boolean recursive) {
        Application application = getApplication(appId, vespaVersion);
        return listConfigs(application, keyToMatch, recursive);
    }

    private Set<ConfigKey<?>> listConfigs(Application application, ConfigKey<?> keyToMatch, boolean recursive) {
        Set<ConfigKey<?>> ret = new LinkedHashSet<>();
        for (ConfigKey<?> key : application.allConfigsProduced()) {
            String configId = key.getConfigId();
            if (recursive) {
                key = new ConfigKey<>(key.getName(), configId, key.getNamespace());
            } else {
                // Include first part of id as id
                key = new ConfigKey<>(key.getName(), configId.split("/")[0], key.getNamespace());
            }
            if (keyToMatch != null) {
                String n = key.getName(); // Never null
                String ns = key.getNamespace(); // Never null
                if (n.equals(keyToMatch.getName()) &&
                    ns.equals(keyToMatch.getNamespace()) &&
                    configId.startsWith(keyToMatch.getConfigId()) &&
                    !(configId.equals(keyToMatch.getConfigId()))) {

                    if (!recursive) {
                        // For non-recursive, include the id segment we were searching for, and first part of the rest
                        key = new ConfigKey<>(key.getName(), appendOneLevelOfId(keyToMatch.getConfigId(), configId), key.getNamespace());
                    }
                    ret.add(key);
                }
            } else {
                ret.add(key);
            }
        }
        return ret;
    }

    @Override
    public Set<ConfigKey<?>> listConfigs(ApplicationId appId, Optional<Version> vespaVersion, boolean recursive) {
        Application application = getApplication(appId, vespaVersion);
        return listConfigs(application, null, recursive);
    }

    /**
     * Given baseIdSegment search/ and id search/qrservers/default.0, return search/qrservers
     * @return id segment with one extra level from the id appended
     */
    String appendOneLevelOfId(String baseIdSegment, String id) {
        if ("".equals(baseIdSegment)) return id.split("/")[0];
        String theRest = id.substring(baseIdSegment.length());
        if ("".equals(theRest)) return id;
        theRest = theRest.replaceFirst("/", "");
        String theRestFirstSeg = theRest.split("/")[0];
        return baseIdSegment+"/"+theRestFirstSeg;
    }

    @Override
    public Set<ConfigKey<?>> allConfigsProduced(ApplicationId appId, Optional<Version> vespaVersion) {
        Application application = getApplication(appId, vespaVersion);
        return application.allConfigsProduced();
    }

    private Application getApplication(ApplicationId appId, Optional<Version> vespaVersion) {
        try {
            return applicationMapper.getForVersion(appId, vespaVersion, clock.instant());
        } catch (VersionDoesNotExistException ex) {
            throw new NotFoundException(String.format("%sNo such application (id %s): %s", TenantRepository.logPre(tenant), appId, ex.getMessage()));
        }
    }

    @Override
    public Set<String> allConfigIds(ApplicationId appId, Optional<Version> vespaVersion) {
        Application application = getApplication(appId, vespaVersion);
        return application.allConfigIds();
    }

    @Override
    public boolean hasApplication(ApplicationId appId, Optional<Version> vespaVersion) {
        return hasHandler(appId, vespaVersion);
    }

    private boolean hasHandler(ApplicationId appId, Optional<Version> vespaVersion) {
        return applicationMapper.hasApplicationForVersion(appId, vespaVersion, clock.instant());
    }

    @Override
    public ApplicationId resolveApplicationId(String hostName) {
        ApplicationId applicationId = hostRegistry.getKeyForHost(hostName);
        if (applicationId == null) {
            applicationId = ApplicationId.defaultId();
        }
        return applicationId;
    }

    @Override
    public Set<FileReference> listFileReferences(ApplicationId applicationId) {
        return applicationMapper.listApplications(applicationId).stream()
                .flatMap(app -> app.getModel().fileReferences().stream())
                .collect(toSet());
    }

    @Override
    public void verifyHosts(ApplicationId key, Collection<String> newHosts) {
        hostRegistry.verifyHosts(key, newHosts);
        reloadListener.verifyHostsAreAvailable(tenant, newHosts);
    }

    public HostValidator<ApplicationId> getHostValidator() {
        return this;
    }

    public HostRegistry<ApplicationId> getApplicationHostRegistry() {
        return hostRegistry;
    }

    public ApplicationId getApplicationIdForHostName(String hostname) {
        return hostRegistry.getKeyForHost(hostname);
    }

    public TenantFileSystemDirs getTenantFileSystemDirs() { return tenantFileSystemDirs; }
}
