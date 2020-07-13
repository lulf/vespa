// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.session;

import com.yahoo.config.application.api.ApplicationMetaData;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.provision.AllocatedHosts;
import com.yahoo.config.provision.TenantName;
import com.yahoo.lang.SettableOptional;
import com.yahoo.transaction.Transaction;
import com.yahoo.vespa.config.server.GlobalComponentRegistry;
import com.yahoo.vespa.config.server.application.ApplicationSet;
import com.yahoo.vespa.config.server.modelfactory.ActivatedModelsBuilder;
import com.yahoo.vespa.curator.Curator;
import org.apache.zookeeper.KeeperException;

import java.time.Clock;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A RemoteSession represents a session created on another config server. This session can
 * be regarded as read only, and this interface only allows reading information about a session.
 *
 * @author Ulf Lilleengen
 */
public class RemoteSession extends Session {

    private static final Logger log = Logger.getLogger(RemoteSession.class.getName());
    private ApplicationSet applicationSet = null;
    private final ActivatedModelsBuilder applicationLoader;
    private final Clock clock;

    /**
     * Creates a session. This involves loading the application, validating it and distributing it.
     *
     * @param tenant The name of the tenant creating session
     * @param sessionId The session id for this session.
     * @param componentRegistry a registry of global components
     * @param zooKeeperClient a SessionZooKeeperClient instance
     */
    public RemoteSession(TenantName tenant,
                         long sessionId,
                         GlobalComponentRegistry componentRegistry,
                         SessionZooKeeperClient zooKeeperClient) {
        super(tenant, sessionId, zooKeeperClient);
        this.applicationLoader = new ActivatedModelsBuilder(tenant, sessionId, zooKeeperClient, componentRegistry);
        this.clock = componentRegistry.getClock();
    }

    void prepare() {
        Curator.CompletionWaiter waiter = sessionZooKeeperClient.getPrepareWaiter();
        ensureApplicationLoaded();
        notifyCompletion(waiter);
    }

    private ApplicationSet loadApplication() {
        ApplicationPackage applicationPackage = sessionZooKeeperClient.loadApplicationPackage();

        // Read hosts allocated on the config server instance which created this
        Optional<AllocatedHosts> allocatedHosts = applicationPackage.getAllocatedHosts();

        return ApplicationSet.fromList(applicationLoader.buildModels(sessionZooKeeperClient.readApplicationId(),
                                                                     sessionZooKeeperClient.readDockerImageRepository(),
                                                                     sessionZooKeeperClient.readVespaVersion(),
                                                                     applicationPackage,
                                                                     new SettableOptional<>(allocatedHosts),
                                                                     clock.instant()));
    }

    public synchronized ApplicationSet ensureApplicationLoaded() {
        return applicationSet == null ? applicationSet = loadApplication() : applicationSet;
    }

    public synchronized void deactivate() {
        applicationSet = null;
    }

    public Transaction createDeleteTransaction() {
        return sessionZooKeeperClient.createWriteStatusTransaction(Status.DELETE);
    }
    
    void confirmUpload() {
        Curator.CompletionWaiter waiter = sessionZooKeeperClient.getUploadWaiter();
        log.log(Level.FINE, "Notifying upload waiter for session " + getSessionId());
        notifyCompletion(waiter);
        log.log(Level.FINE, "Done notifying upload for session " + getSessionId());
    }

    void notifyCompletion(Curator.CompletionWaiter completionWaiter) {
        try {
            completionWaiter.notifyCompletion();
        } catch (RuntimeException e) {
            // Throw only if we get something else than NoNodeException -- NoNodeException might happen when
            // the session is no longer in use (e.g. the app using this session has been deleted) and this method
            // has not been called yet for the previous session operation
            // on a minority of the config servers (see awaitInternal() method in this class)
            if (e.getCause().getClass() != KeeperException.NoNodeException.class) {
                throw e;
            } else {
                log.log(Level.INFO, "Not able to notify completion for session: " + getSessionId() + ", node has been deleted");
            }
        }
    }

    public void delete() {
        Transaction transaction = sessionZooKeeperClient.deleteTransaction();
        transaction.commit();
        transaction.close();
    }

    public ApplicationMetaData getMetaData() {
        return sessionZooKeeperClient.loadApplicationPackage().getMetaData();
    }

}
