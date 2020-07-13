// Copyright 2019 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.flags;

import com.yahoo.component.Vtag;
import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.flags.custom.HostCapacity;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static com.yahoo.vespa.flags.FetchVector.Dimension.APPLICATION_ID;
import static com.yahoo.vespa.flags.FetchVector.Dimension.CONSOLE_USER_EMAIL;
import static com.yahoo.vespa.flags.FetchVector.Dimension.HOSTNAME;
import static com.yahoo.vespa.flags.FetchVector.Dimension.NODE_TYPE;
import static com.yahoo.vespa.flags.FetchVector.Dimension.VESPA_VERSION;
import static com.yahoo.vespa.flags.FetchVector.Dimension.ZONE_ID;

/**
 * Definitions of feature flags.
 *
 * <p>To use feature flags, define the flag in this class as an "unbound" flag, e.g. {@link UnboundBooleanFlag}
 * or {@link UnboundStringFlag}. At the location you want to get the value of the flag, you need the following:</p>
 *
 * <ol>
 *     <li>The unbound flag</li>
 *     <li>A {@link FlagSource}. The flag source is typically available as an injectable component. Binding
 *     an unbound flag to a flag source produces a (bound) flag, e.g. {@link BooleanFlag} and {@link StringFlag}.</li>
 *     <li>If you would like your flag value to be dependent on e.g. the application ID, then 1. you should
 *     declare this in the unbound flag definition in this file (referring to
 *     {@link FetchVector.Dimension#APPLICATION_ID}), and 2. specify the application ID when retrieving the value, e.g.
 *     {@link BooleanFlag#with(FetchVector.Dimension, String)}. See {@link FetchVector} for more info.</li>
 * </ol>
 *
 * <p>Once the code is in place, you can override the flag value. This depends on the flag source, but typically
 * there is a REST API for updating the flags in the config server, which is the root of all flag sources in the zone.</p>
 *
 * @author hakonhall
 */
public class Flags {
    private static volatile TreeMap<FlagId, FlagDefinition> flags = new TreeMap<>();

    public static final UnboundIntFlag DROP_CACHES = defineIntFlag(
            "drop-caches", 3,
            "The int value to write into /proc/sys/vm/drop_caches for each tick. " +
            "1 is page cache, 2 is dentries inodes, 3 is both page cache and dentries inodes, etc.",
            "Takes effect on next tick.",
            HOSTNAME);

    public static final UnboundBooleanFlag ENABLE_CROWDSTRIKE = defineFeatureFlag(
            "enable-crowdstrike", true,
            "Whether to enable CrowdStrike.", "Takes effect on next host admin tick",
            HOSTNAME);

    public static final UnboundBooleanFlag ENABLE_NESSUS = defineFeatureFlag(
            "enable-nessus", true,
            "Whether to enable Nessus.", "Takes effect on next host admin tick",
            HOSTNAME);

    public static final UnboundBooleanFlag ENABLE_FLEET_SSHD_CONFIG = defineFeatureFlag(
            "enable-fleet-sshd-config", true,
            "Whether fleet should manage the /etc/ssh/sshd_config file.",
            "Takes effect on next host admin tick.",
            HOSTNAME);

    public static final UnboundBooleanFlag FLEET_CANARY = defineFeatureFlag(
            "fleet-canary", false,
            "Whether the host is a fleet canary.",
            "Takes effect on next host admin tick.",
            HOSTNAME);

    public static final UnboundListFlag<String> DISABLED_HOST_ADMIN_TASKS = defineListFlag(
            "disabled-host-admin-tasks", List.of(), String.class,
            "List of host-admin task names (as they appear in the log, e.g. root>main>UpgradeTask) that should be skipped",
            "Takes effect on next host admin tick",
            HOSTNAME, NODE_TYPE);

    public static final UnboundStringFlag DOCKER_VERSION = defineStringFlag(
            "docker-version", "1.13.1-102.git7f2769b",
            "The version of the docker to use of the format VERSION-REL: The YUM package to be installed will be " +
            "2:docker-VERSION-REL.el7.centos.x86_64 in AWS (and without '.centos' otherwise). " +
            "If docker-version is not of this format, it must be parseable by YumPackageName::fromString.",
            "Takes effect on next tick.",
            HOSTNAME);

    public static final UnboundLongFlag THIN_POOL_GB = defineLongFlag(
            "thin-pool-gb", -1,
            "The size of the disk reserved for the thin pool with dynamic provisioning in AWS, in base-2 GB. " +
                    "If <0, the default is used (which may depend on the zone and node type).",
            "Takes effect immediately (but used only during provisioning).",
            NODE_TYPE);

    public static final UnboundDoubleFlag CONTAINER_CPU_CAP = defineDoubleFlag(
            "container-cpu-cap", 0,
            "Hard limit on how many CPUs a container may use. This value is multiplied by CPU allocated to node, so " +
            "to cap CPU at 200%, set this to 2, etc.",
            "Takes effect on next node agent tick. Change is orchestrated, but does NOT require container restart",
            HOSTNAME, APPLICATION_ID);

    public static final UnboundStringFlag TLS_INSECURE_AUTHORIZATION_MODE = defineStringFlag(
            "tls-insecure-authorization-mode", "log_only",
            "TLS insecure authorization mode. Allowed values: ['disable', 'log_only', 'enforce']",
            "Takes effect on restart of Docker container",
            NODE_TYPE, APPLICATION_ID, HOSTNAME);

    public static final UnboundIntFlag REBOOT_INTERVAL_IN_DAYS = defineIntFlag(
            "reboot-interval-in-days", 30,
            "No reboots are scheduled 0x-1x reboot intervals after the previous reboot, while reboot is " +
            "scheduled evenly distributed in the 1x-2x range (and naturally guaranteed at the 2x boundary).",
            "Takes effect on next run of NodeRebooter");

    public static final UnboundBooleanFlag RETIRE_WITH_PERMANENTLY_DOWN = defineFeatureFlag(
            "retire-with-permanently-down", false,
            "If enabled, retirement will end with setting the host status to PERMANENTLY_DOWN, " +
            "instead of ALLOWED_TO_BE_DOWN (old behavior).",
            "Takes effect on the next run of RetiredExpirer.",
            HOSTNAME);

    public static final UnboundListFlag<HostCapacity> TARGET_CAPACITY = defineListFlag(
            "preprovision-capacity", List.of(), HostCapacity.class,
            "List of node resources and their count that should be provisioned." +
            "In a dynamically provisioned zone this specifies the unallocated (i.e. pre-provisioned) capacity. " +
            "Otherwise it specifies the total (unallocated or not) capacity.",
            "Takes effect on next iteration of DynamicProvisioningMaintainer.");

    public static final UnboundListFlag<String> INACTIVE_MAINTENANCE_JOBS = defineListFlag(
            "inactive-maintenance-jobs", List.of(), String.class,
            "The list of maintenance jobs that are inactive.",
            "Takes effect immediately, but any currently running jobs will run until completion.");

    public static final UnboundDoubleFlag DEFAULT_TERM_WISE_LIMIT = defineDoubleFlag(
            "default-term-wise-limit", 1.0,
            "Default limit for when to apply termwise query evaluation",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundDoubleFlag DEFAULT_THREADPOOL_SIZE_FACTOR = defineDoubleFlag(
            "default-threadpool-size-factor", 0.0,
            "Default multiplication factor when computing maxthreads for main container threadpool based on available cores",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);
    public static final UnboundDoubleFlag DEFAULT_QUEUE_SIZE_FACTOR = defineDoubleFlag(
            "default-queue-size-factor", 0.0,
            "Default multiplication factor when computing queuesize for burst handling",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundStringFlag JVM_GC_OPTIONS = defineStringFlag(
            "jvm-gc-options", "",
            "Sets deafult jvm gc options",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundStringFlag FEED_SEQUENCER_TYPE = defineStringFlag(
            "feed-sequencer-type", "LATENCY",
            "Selects type of sequenced executor used for feeding, valid values are LATENCY, ADAPTIVE, THROUGHPUT",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);
    public static final UnboundStringFlag RESPONSE_SEQUENCER_TYPE = defineStringFlag(
            "response-sequencer-type", "ADAPTIVE",
            "Selects type of sequenced executor used for mbus responses, valid values are LATENCY, ADAPTIVE, THROUGHPUT",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);
    public static final UnboundIntFlag RESPONSE_NUM_THREADS = defineIntFlag(
            "response-num-threads", 2,
            "Number of threads used for mbus responses, default is 2, negative number = numcores/4",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);
    public static final UnboundBooleanFlag SKIP_COMMUNICATIONMANAGER_THREAD = defineFeatureFlag(
            "skip-communicatiomanager-thread", false,
            "Should we skip the communicationmanager thread",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);
    public static final UnboundBooleanFlag SKIP_MBUS_REQUEST_THREAD = defineFeatureFlag(
            "skip-mbus-request-thread", false,
            "Should we skip the mbus request thread",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag SKIP_MBUS_REPLY_THREAD = defineFeatureFlag(
            "skip-mbus-reply-thread", false,
            "Should we skip the mbus reply thread",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag USE_CONTENT_NODE_BTREE_DB = defineFeatureFlag(
            "use-content-node-btree-db", false,
            "Whether to use the new B-tree bucket database on the content node.",
            "Takes effect at restart of content node process",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag USE_THREE_PHASE_UPDATES = defineFeatureFlag(
            "use-three-phase-updates", false,
            "Whether to enable the use of three-phase updates when bucket replicas are out of sync.",
            "Takes effect at redeployment",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag HOST_HARDENING = defineFeatureFlag(
            "host-hardening", false,
            "Whether to enable host hardening Linux baseline.",
            "Takes effect on next tick or on host-admin restart (may vary where used).",
            HOSTNAME);

    public static final UnboundBooleanFlag TCP_ABORT_ON_OVERFLOW = defineFeatureFlag(
            "tcp-abort-on-overflow", false,
            "Whether to set /proc/sys/net/ipv4/tcp_abort_on_overflow to 0 (false) or 1 (true)",
            "Takes effect on next host-admin tick.",
            HOSTNAME);

    public static final UnboundStringFlag ZOOKEEPER_SERVER_VERSION = defineStringFlag(
            "zookeeper-server-version", "3.5.6",
            "ZooKeeper server version, a jar file zookeeper-server-<ZOOKEEPER_SERVER_VERSION>-jar-with-dependencies.jar must exist",
            "Takes effect on restart of Docker container",
            NODE_TYPE, APPLICATION_ID, HOSTNAME);

    public static final UnboundStringFlag TLS_FOR_ZOOKEEPER_CLIENT_SERVER_COMMUNICATION = defineStringFlag(
            "tls-for-zookeeper-client-server-communication", "OFF",
            "How to setup TLS for ZooKeeper client/server communication. Valid values are OFF, PORT_UNIFICATION, TLS_WITH_PORT_UNIFICATION, TLS_ONLY",
            "Takes effect on restart of config server",
            NODE_TYPE, HOSTNAME);

    public static final UnboundBooleanFlag USE_TLS_FOR_ZOOKEEPER_CLIENT = defineFeatureFlag(
            "use-tls-for-zookeeper-client", false,
            "Whether to use TLS for ZooKeeper clients",
            "Takes effect on restart of process",
            NODE_TYPE, HOSTNAME);

    public static final UnboundBooleanFlag ENABLE_DISK_WRITE_TEST = defineFeatureFlag(
            "enable-disk-write-test", true,
            "Regularly issue a small write to disk and fail the host if it is not successful",
            "Takes effect on next node agent tick (but does not clear existing failure reports)",
            HOSTNAME);

    public static final UnboundBooleanFlag USE_REFRESHED_ENDPOINT_CERTIFICATE = defineFeatureFlag(
            "use-refreshed-endpoint-certificate", false,
            "Whether an application should start using a newer certificate/key pair if available",
            "Takes effect on the next deployment of the application",
            APPLICATION_ID);

    public static final UnboundBooleanFlag VALIDATE_ENDPOINT_CERTIFICATES = defineFeatureFlag(
            "validate-endpoint-certificates", false,
            "Whether endpoint certificates should be validated before use",
            "Takes effect on the next deployment of the application");

    public static final UnboundStringFlag ENDPOINT_CERTIFICATE_BACKFILL = defineStringFlag(
            "endpoint-certificate-backfill", "disable",
            "Whether the endpoint certificate maintainer should backfill missing certificate data from cameo",
            "Takes effect on next scheduled run of maintainer - set to \"disable\", \"dryrun\" or \"enable\"");

    public static final UnboundStringFlag DOCKER_IMAGE_REPO = defineStringFlag(
            "docker-image-repo", "",
            "Override default docker image repo. Docker image version will be Vespa version.",
            "Takes effect on next deployment from controller",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag ENDPOINT_CERT_IN_SHARED_ROUTING = defineFeatureFlag(
            "endpoint-cert-in-shared-routing", false,
            "Whether to provision and use endpoint certs for apps in shared routing zones",
            "Takes effect on next deployment of the application", APPLICATION_ID);

    public static final UnboundBooleanFlag PHRASE_SEGMENTING = defineFeatureFlag(
            "phrase-segmenting", false,
            "Should 'implicit phrases' in queries we parsed to a phrase or and?",
            "Takes effect on redeploy",
            ZONE_ID, APPLICATION_ID);

    public static final UnboundBooleanFlag ALLOW_DIRECT_ROUTING = defineFeatureFlag(
            "publish-direct-routing-endpoint", true,
            "Whether an application should receive a directly routed endpoint in its endpoint list",
            "Takes effect immediately",
            APPLICATION_ID);

    public static final UnboundBooleanFlag NLB_PROXY_PROTOCOL = defineFeatureFlag(
            "nlb-proxy-protocol", false,
            "Configure NLB to use proxy protocol",
            "Takes effect on next application redeploy",
            APPLICATION_ID);

    public static final UnboundBooleanFlag USE_CLOUD_INIT_FORMAT = defineFeatureFlag(
            "use-cloud-init", false,
            "Use the cloud-init format when provisioning hosts",
            "Takes effect immediately",
            ZONE_ID);

    public static final UnboundBooleanFlag CONFIGSERVER_DISTRIBUTE_APPLICATION_PACKAGE = defineFeatureFlag(
            "configserver-distribute-application-package", false,
            "Whether the application package should be distributed to other config servers during a deployment",
            "Takes effect immediately");

    public static final UnboundBooleanFlag PROVISION_APPLICATION_ROLES = defineFeatureFlag(
            "provision-application-roles", false,
            "Whether application roles should be provisioned",
            "Takes effect on next deployment (controller)",
            ZONE_ID);

    public static final UnboundBooleanFlag APPLICATION_IAM_ROLE = defineFeatureFlag(
            "application-iam-roles", false,
            "Allow separate iam roles when provisioning/assigning hosts",
            "Takes effect immediately on new hosts, on next redeploy for applications",
            APPLICATION_ID);

    public static final UnboundBooleanFlag ENABLE_PUBLIC_SIGNUP_FLOW = defineFeatureFlag(
            "enable-public-signup-flow", false,
            "Show the public signup flow for a user in the console",
            "takes effect on browser reload of api/user/v1/user",
            CONSOLE_USER_EMAIL
    );

    public static final UnboundBooleanFlag CONTROLLER_PROVISION_LB = defineFeatureFlag(
            "controller-provision-lb", false,
            "Provision load balancer for controller cluster",
            "Takes effect when controller application is redeployed",
            ZONE_ID
    );

    public static final UnboundIntFlag TENANT_NODE_QUOTA = defineIntFlag(
            "tenant-node-quota", 5,
            "The number of nodes a tenant is allowed to request",
            "Takes effect on next deployment",
            APPLICATION_ID
    );

    public static final UnboundBooleanFlag WEIGHTED_DNS_PER_REGION = defineFeatureFlag(
            "weighted-dns-per-region", false,
            "Whether to create weighted DNS records per region in global endpoints",
            "Takes effect on next deployment through controller",
            APPLICATION_ID
    );

    public static final UnboundBooleanFlag ONLY_PUBLIC_ACCESS = defineFeatureFlag(
            "enable-public-only", false,
            "Only access public hosts from container",
            "Takes effect on next tick"
    );

    public static final UnboundListFlag<String> OUTBOUND_BLOCKED_IPV4 = defineListFlag(
            "container-outbound-blocked-ipv4", List.of(), String.class,
            "List of IPs or CIDRs that are blocked for outbound connections",
            "Takes effect on next tick"
    );

    public static final UnboundListFlag<String> OUTBOUND_BLOCKED_IPV6 = defineListFlag(
            "container-outbound-blocked-ipv6", List.of(), String.class,
            "List of IPs or CIDRs that are blocked for outbound connections",
            "Takes effect on next tick"
    );

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundBooleanFlag defineFeatureFlag(String flagId, boolean defaultValue, String description,
                                                       String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundBooleanFlag::new, flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundStringFlag defineStringFlag(String flagId, String defaultValue, String description,
                                                     String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundStringFlag::new, flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundIntFlag defineIntFlag(String flagId, int defaultValue, String description,
                                               String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundIntFlag::new, flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundLongFlag defineLongFlag(String flagId, long defaultValue, String description,
                                                 String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundLongFlag::new, flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundDoubleFlag defineDoubleFlag(String flagId, double defaultValue, String description,
                                                     String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundDoubleFlag::new, flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static <T> UnboundJacksonFlag<T> defineJacksonFlag(String flagId, T defaultValue, Class<T> jacksonClass, String description,
                                                              String modificationEffect, FetchVector.Dimension... dimensions) {
        return define((id2, defaultValue2, vector2) -> new UnboundJacksonFlag<>(id2, defaultValue2, vector2, jacksonClass),
                flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static <T> UnboundListFlag<T> defineListFlag(String flagId, List<T> defaultValue, Class<T> elementClass,
                                                        String description, String modificationEffect, FetchVector.Dimension... dimensions) {
        return define((fid, dval, fvec) -> new UnboundListFlag<>(fid, dval, elementClass, fvec),
                flagId, defaultValue, description, modificationEffect, dimensions);
    }

    @FunctionalInterface
    private interface TypedUnboundFlagFactory<T, U extends UnboundFlag<?, ?, ?>> {
        U create(FlagId id, T defaultVale, FetchVector defaultFetchVector);
    }

    /**
     * Defines a Flag.
     *
     * @param factory            Factory for creating unbound flag of type U
     * @param flagId             The globally unique FlagId.
     * @param defaultValue       The default value if none is present after resolution.
     * @param description        Description of how the flag is used.
     * @param modificationEffect What is required for the flag to take effect? A restart of process? immediately? etc.
     * @param dimensions         What dimensions will be set in the {@link FetchVector} when fetching
     *                           the flag value in
     *                           {@link FlagSource#fetch(FlagId, FetchVector) FlagSource::fetch}.
     *                           For instance, if APPLICATION is one of the dimensions here, you should make sure
     *                           APPLICATION is set to the ApplicationId in the fetch vector when fetching the RawFlag
     *                           from the FlagSource.
     * @param <T>                The boxed type of the flag value, e.g. Boolean for flags guarding features.
     * @param <U>                The type of the unbound flag, e.g. UnboundBooleanFlag.
     * @return An unbound flag with {@link FetchVector.Dimension#HOSTNAME HOSTNAME} and
     *         {@link FetchVector.Dimension#VESPA_VERSION VESPA_VERSION} already set. The ZONE environment
     *         is typically implicit.
     */
    private static <T, U extends UnboundFlag<?, ?, ?>> U define(TypedUnboundFlagFactory<T, U> factory,
                                                                String flagId,
                                                                T defaultValue,
                                                                String description,
                                                                String modificationEffect,
                                                                FetchVector.Dimension[] dimensions) {
        FlagId id = new FlagId(flagId);
        FetchVector vector = new FetchVector()
                .with(HOSTNAME, Defaults.getDefaults().vespaHostname())
                // Warning: In unit tests and outside official Vespa releases, the currentVersion is e.g. 7.0.0
                // (determined by the current major version). Consider not setting VESPA_VERSION if minor = micro = 0.
                .with(VESPA_VERSION, Vtag.currentVersion.toFullString());
        U unboundFlag = factory.create(id, defaultValue, vector);
        FlagDefinition definition = new FlagDefinition(unboundFlag, description, modificationEffect, dimensions);
        flags.put(id, definition);
        return unboundFlag;
    }

    public static List<FlagDefinition> getAllFlags() {
        return List.copyOf(flags.values());
    }

    public static Optional<FlagDefinition> getFlag(FlagId flagId) {
        return Optional.ofNullable(flags.get(flagId));
    }

    /**
     * Allows the statically defined flags to be controlled in a test.
     *
     * <p>Returns a Replacer instance to be used with e.g. a try-with-resources block. Within the block,
     * the flags starts out as cleared. Flags can be defined, etc. When leaving the block, the flags from
     * before the block is reinserted.
     *
     * <p>NOT thread-safe. Tests using this cannot run in parallel.
     */
    public static Replacer clearFlagsForTesting() {
        return new Replacer();
    }

    public static class Replacer implements AutoCloseable {
        private static volatile boolean flagsCleared = false;

        private final TreeMap<FlagId, FlagDefinition> savedFlags;

        private Replacer() {
            verifyAndSetFlagsCleared(true);
            this.savedFlags = Flags.flags;
            Flags.flags = new TreeMap<>();
        }

        @Override
        public void close() {
            verifyAndSetFlagsCleared(false);
            Flags.flags = savedFlags;
        }

        /**
         * Used to implement a simple verification that Replacer is not used by multiple threads.
         * For instance two different tests running in parallel cannot both use Replacer.
         */
        private static void verifyAndSetFlagsCleared(boolean newValue) {
            if (flagsCleared == newValue) {
                throw new IllegalStateException("clearFlagsForTesting called while already cleared - running tests in parallell!?");
            }
            flagsCleared = newValue;
        }
    }
}
