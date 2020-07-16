// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.ApplicationController;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.api.integration.organization.ApplicationSummary;
import com.yahoo.vespa.hosted.controller.api.integration.organization.IssueId;
import com.yahoo.vespa.hosted.controller.api.integration.organization.OwnershipIssues;
import com.yahoo.vespa.hosted.controller.api.integration.organization.User;
import com.yahoo.vespa.hosted.controller.application.ApplicationList;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.tenant.Tenant;
import com.yahoo.yolean.Exceptions;

import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Periodically request application ownership confirmation through filing issues.
 *
 * When to file new issues, escalate inactive ones, etc., is handled by the enclosed OwnershipIssues.
 *
 * @author jonmv
 */
public class ApplicationOwnershipConfirmer extends ControllerMaintainer {

    private final OwnershipIssues ownershipIssues;
    private final ApplicationController applications;

    public ApplicationOwnershipConfirmer(Controller controller, Duration interval, OwnershipIssues ownershipIssues) {
        super(controller, interval);
        this.ownershipIssues = ownershipIssues;
        this.applications = controller.applications();
    }

    @Override
    protected boolean maintain() {
        return confirmApplicationOwnerships() &
               ensureConfirmationResponses() &
               updateConfirmedApplicationOwners();
    }

    /** File an ownership issue with the owners of all applications we know about. */
    private boolean confirmApplicationOwnerships() {
        AtomicBoolean success = new AtomicBoolean(true);
        applications()
                       .withProjectId()
                       .withProductionDeployment()
                       .asList()
                       .stream()
                       .filter(application -> application.createdAt().isBefore(controller().clock().instant().minus(Duration.ofDays(90))))
                       .forEach(application -> {
                           try {
                               // TODO jvenstad: Makes sense to require, and run this only in main?
                               tenantOf(application.id()).contact().flatMap(contact -> {
                                   return ownershipIssues.confirmOwnership(application.ownershipIssueId(),
                                                                           summaryOf(application.id()),
                                                                           determineAssignee(application),
                                                                           contact);
                               }).ifPresent(newIssueId -> store(newIssueId, application.id()));
                           }
                           catch (RuntimeException e) { // Catch errors due to wrong data in the controller, or issues client timeout.
                               success.set(false);
                               log.log(Level.INFO, "Exception caught when attempting to file an issue for '" + application.id() + "': " + Exceptions.toMessageString(e));
                           }
                       });
        return success.get();
    }

    private ApplicationSummary summaryOf(TenantAndApplicationId application) {
        var app = applications.requireApplication(application);
        var metrics = new HashMap<ZoneId, ApplicationSummary.Metric>();
        for (Instance instance : app.instances().values())
            for (var kv : instance.deployments().entrySet()) {
                var zone = kv.getKey();
                var deploymentMetrics = kv.getValue().metrics();
                metrics.put(zone, new ApplicationSummary.Metric(deploymentMetrics.documentCount(),
                                                                deploymentMetrics.queriesPerSecond(),
                                                                deploymentMetrics.writesPerSecond()));
            }
        return new ApplicationSummary(app.id().defaultInstance(), app.activity().lastQueried(), app.activity().lastWritten(),
                                      app.latestVersion().flatMap(version -> version.buildTime()), metrics);
    }

    /** Escalate ownership issues which have not been closed before a defined amount of time has passed. */
    private boolean ensureConfirmationResponses() {
        AtomicBoolean success = new AtomicBoolean(true);
        for (Application application : applications())
            application.ownershipIssueId().ifPresent(issueId -> {
                try {
                    Tenant tenant = tenantOf(application.id());
                    ownershipIssues.ensureResponse(issueId, tenant.contact());
                }
                catch (RuntimeException e) {
                    success.set(false);
                    log.log(Level.INFO, "Exception caught when attempting to escalate issue with id '" + issueId + "': " + Exceptions.toMessageString(e));
                }
            });
        return success.get();
    }

    private boolean updateConfirmedApplicationOwners() {
        applications()
                       .withProjectId()
                       .withProductionDeployment()
                       .asList()
                       .stream()
                       .filter(application -> application.ownershipIssueId().isPresent())
                       .forEach(application -> {
                           IssueId ownershipIssueId = application.ownershipIssueId().get();
                           ownershipIssues.getConfirmedOwner(ownershipIssueId).ifPresent(owner -> {
                               controller().applications().lockApplicationIfPresent(application.id(), lockedApplication ->
                                       controller().applications().store(lockedApplication.withOwner(owner)));
                           });
                       });
        return true;
    }

    private ApplicationList applications() {
        return ApplicationList.from(controller().applications().readable());
    }

    private User determineAssignee(Application application) {
        return application.owner().orElse(null);
    }

    private Tenant tenantOf(TenantAndApplicationId applicationId) {
        return controller().tenants().get(applicationId.tenant())
                .orElseThrow(() -> new IllegalStateException("No tenant found for application " + applicationId));
    }

    protected void store(IssueId issueId, TenantAndApplicationId applicationId) {
        controller().applications().lockApplicationIfPresent(applicationId, application ->
                controller().applications().store(application.withOwnershipIssueId(issueId)));
    }
}
