// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.session;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.component.Version;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.model.NullConfigModelRegistry;
import com.yahoo.config.model.api.Model;
import com.yahoo.config.model.api.ModelContext;
import com.yahoo.config.model.api.ModelCreateResult;
import com.yahoo.config.model.api.ModelFactory;
import com.yahoo.config.model.api.ValidationParameters;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.model.test.MockApplicationPackage;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.config.server.TestComponentRegistry;
import com.yahoo.vespa.config.server.application.ApplicationSet;
import com.yahoo.vespa.config.server.application.PermanentApplicationPackage;
import com.yahoo.vespa.config.server.modelfactory.ModelFactoryRegistry;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.curator.mock.MockCurator;
import com.yahoo.vespa.model.VespaModel;
import com.yahoo.vespa.model.VespaModelFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * @author Ulf Lilleengen
 * @author bratseth
 */
public class RemoteSessionTest {

    private static final TenantName tenantName = TenantName.from("default");

    private Curator curator;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setupTest() {
        curator = new MockCurator();
    }

    @Test
    public void require_that_session_is_initialized() {
        Clock clock = Clock.systemUTC();
        Session session = createSession(2, clock);
        assertThat(session.getSessionId(), is(2L));
        session = createSession(Long.MAX_VALUE, clock);
        assertThat(session.getSessionId(), is(Long.MAX_VALUE));
    }

    @Test
    public void require_that_applications_are_loaded() {
        RemoteSession session = createSession(3, Arrays.asList(new MockModelFactory(), new VespaModelFactory(new NullConfigModelRegistry())));
        session.prepare();
        ApplicationSet applicationSet = session.ensureApplicationLoaded();
        assertNotNull(applicationSet);
        assertThat(applicationSet.getApplicationGeneration(), is(3L));
        assertThat(applicationSet.getForVersionOrLatest(Optional.empty(), Instant.now()).getId().application().value(), is("foo"));
        assertNotNull(applicationSet.getForVersionOrLatest(Optional.empty(), Instant.now()).getModel());
        session.deactivate();

        applicationSet = session.ensureApplicationLoaded();
        assertNotNull(applicationSet);
        assertThat(applicationSet.getApplicationGeneration(), is(3L));
        assertThat(applicationSet.getForVersionOrLatest(Optional.empty(), Instant.now()).getId().application().value(), is("foo"));
        assertNotNull(applicationSet.getForVersionOrLatest(Optional.empty(), Instant.now()).getModel());
    }

    @Test(expected = IllegalArgumentException.class)
    public void require_that_new_invalid_application_throws_exception() {
        MockModelFactory failingFactory = new MockModelFactory();
        failingFactory.vespaVersion = new Version(1, 2, 0);
        failingFactory.throwOnLoad = true;

        MockModelFactory okFactory = new MockModelFactory();
        okFactory.vespaVersion = new Version(1, 1, 0);
        okFactory.throwOnLoad = false;

        RemoteSession session = createSession(3, Arrays.asList(okFactory, failingFactory));
        session.prepare();
    }

    @Test
    public void require_that_old_invalid_application_does_not_throw_exception_if_skipped_also_across_major_versions() {
        MockModelFactory failingFactory = new MockModelFactory();
        failingFactory.vespaVersion = new Version(1, 0, 0);
        failingFactory.throwOnLoad = true;

        MockModelFactory okFactory =
                new MockModelFactory("<validation-overrides><allow until='2000-01-30'>skip-old-config-models</allow></validation-overrides>");
        okFactory.vespaVersion = new Version(2, 0, 0);
        okFactory.throwOnLoad = false;

        RemoteSession session = createSession(3, Arrays.asList(okFactory, failingFactory), failingFactory.clock());
        session.prepare();
    }

    @Test
    public void require_that_an_application_package_can_limit_to_one_major_version() {
        ApplicationPackage application =
                new MockApplicationPackage.Builder().withServices("<services version='1.0'/>")
                                                    .withDeploymentSpec("<deployment version='1.0' major-version='2'/>")
                                                    .build();
        assertTrue(application.getMajorVersion().isPresent());
        assertEquals(2, (int)application.getMajorVersion().get());

        MockModelFactory failingFactory = new MockModelFactory();
        failingFactory.vespaVersion = new Version(3, 0, 0);
        failingFactory.throwErrorOnLoad = true;

        MockModelFactory okFactory = new MockModelFactory();
        okFactory.vespaVersion = new Version(2, 0, 0);
        okFactory.throwErrorOnLoad = false;

        SessionZooKeeperClient zkc = new MockSessionZKClient(curator, tenantName, 3, application);
        RemoteSession session = createSession(3, zkc, Arrays.asList(okFactory, failingFactory));
        session.prepare();

        // Does not cause an error because model version 3 is skipped
    }

    @Test
    public void require_that_an_application_package_can_limit_to_one_higher_major_version() {
        ApplicationPackage application =
                new MockApplicationPackage.Builder().withServices("<services version='1.0'/>")
                                                    .withDeploymentSpec("<deployment version='1.0' major-version='3'/>")
                                                    .build();
        assertTrue(application.getMajorVersion().isPresent());
        assertEquals(3, (int)application.getMajorVersion().get());

        MockModelFactory failingFactory = new MockModelFactory();
        failingFactory.vespaVersion = new Version(4, 0, 0);
        failingFactory.throwErrorOnLoad = true;

        MockModelFactory okFactory = new MockModelFactory();
        okFactory.vespaVersion = new Version(2, 0, 0);
        okFactory.throwErrorOnLoad = false;

        SessionZooKeeperClient zkc = new MockSessionZKClient(curator, tenantName, 3, application);
        RemoteSession session = createSession(4, zkc, Arrays.asList(okFactory, failingFactory));
        session.prepare();

        // Does not cause an error because model version 4 is skipped
    }

    @Test
    public void require_that_session_status_is_updated() {
        SessionZooKeeperClient zkc = new MockSessionZKClient(curator, tenantName, 3);
        RemoteSession session = createSession(3, zkc, Clock.systemUTC());
        assertThat(session.getStatus(), is(Session.Status.NEW));
        zkc.writeStatus(Session.Status.PREPARE);
        assertThat(session.getStatus(), is(Session.Status.PREPARE));
    }

    @Test
    public void require_that_permanent_app_is_used() throws IOException {
        Optional<PermanentApplicationPackage> permanentApp = Optional.of(new PermanentApplicationPackage(
                new ConfigserverConfig(new ConfigserverConfig.Builder()
                                               .applicationDirectory(temporaryFolder.newFolder("appdir").getAbsolutePath()))));
        MockModelFactory mockModelFactory = new MockModelFactory();
        try {
            int sessionId = 3;
            SessionZooKeeperClient zkc = new MockSessionZKClient(curator, tenantName, sessionId);
            createSession(sessionId, zkc, Collections.singletonList(mockModelFactory), permanentApp, Clock.systemUTC()).ensureApplicationLoaded();
        } catch (Exception e) {
            e.printStackTrace();
            // ignore, we're not interested in deploy errors as long as the below state is OK.
        }
        assertNotNull(mockModelFactory.modelContext);
        assertTrue(mockModelFactory.modelContext.permanentApplicationPackage().isPresent());
    }

    private RemoteSession createSession(long sessionId, Clock clock) {
        return createSession(sessionId, Collections.singletonList(new VespaModelFactory(new NullConfigModelRegistry())), clock);
    }

    private RemoteSession createSession(long sessionId, SessionZooKeeperClient zkc, Clock clock) {
        return createSession(sessionId, zkc, Collections.singletonList(new VespaModelFactory(new NullConfigModelRegistry())), clock);
    }

    private RemoteSession createSession(long sessionId, List<ModelFactory> modelFactories) {
        SessionZooKeeperClient zkc = new MockSessionZKClient(curator, tenantName, sessionId);
        return createSession(sessionId, zkc, modelFactories, Clock.systemUTC());
    }

    private RemoteSession createSession(long sessionId, List<ModelFactory> modelFactories, Clock clock) {
        SessionZooKeeperClient zkc = new MockSessionZKClient(curator, tenantName, sessionId);
        return createSession(sessionId, zkc, modelFactories, clock);
    }

    private RemoteSession createSession(long sessionId, SessionZooKeeperClient zkc, List<ModelFactory> modelFactories) {
        return createSession(sessionId, zkc, modelFactories, Optional.empty(), Clock.systemUTC());
    }

    private RemoteSession createSession(long sessionId, SessionZooKeeperClient zkc, List<ModelFactory> modelFactories, Clock clock) {
        return createSession(sessionId, zkc, modelFactories, Optional.empty(), clock);
    }

    private RemoteSession createSession(long sessionId, SessionZooKeeperClient zkc,
                                        List<ModelFactory> modelFactories,
                                        Optional<PermanentApplicationPackage> permanentApplicationPackage,
                                        Clock clock) {
        zkc.writeStatus(Session.Status.NEW);
        zkc.writeApplicationId(new ApplicationId.Builder().applicationName("foo").instanceName("bim").build());
        TestComponentRegistry.Builder registryBuilder = new TestComponentRegistry.Builder()
                .curator(curator)
                .clock(clock)
                .modelFactoryRegistry(new ModelFactoryRegistry(modelFactories));
        permanentApplicationPackage.ifPresent(registryBuilder::permanentApplicationPackage);

        return new RemoteSession(tenantName, sessionId, registryBuilder.build(), zkc);
    }

    private class MockModelFactory implements ModelFactory {

        /** Throw a RuntimeException on load - this is handled gracefully during model building */
        boolean throwOnLoad = false;

        /** Throw an Error on load - this is useful to propagate this condition all the way to the test */
        boolean throwErrorOnLoad = false;

        ModelContext modelContext;
        public Version vespaVersion = new Version(1, 2, 3);

        /** The validation overrides of this, or null if none */
        private final String validationOverrides;
        
        private Clock clock = Clock.fixed(LocalDate.parse("2000-01-01", DateTimeFormatter.ISO_DATE).atStartOfDay().atZone(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

        MockModelFactory() { this(null); }

        MockModelFactory(String validationOverrides) {
            this.validationOverrides = validationOverrides;
        }

        @Override
        public Version version() {
            return vespaVersion;
        }
        
        /** Returns the clock used by this, which is fixed at the instant 2000-01-01T00:00:00 */
        public Clock clock() { return clock; }

        @Override
        public Model createModel(ModelContext modelContext) {
            if (throwErrorOnLoad)
                throw new Error("Foo");
            if (throwOnLoad)
                throw new IllegalArgumentException("Foo");
            this.modelContext = modelContext;
            return loadModel();
        }

        Model loadModel() {
            try {
                ApplicationPackage application = new MockApplicationPackage.Builder().withEmptyHosts().withEmptyServices().withValidationOverrides(validationOverrides).build();
                DeployState deployState = new DeployState.Builder().applicationPackage(application).now(clock.instant()).build();
                return new VespaModel(deployState);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public ModelCreateResult createAndValidateModel(ModelContext modelContext, ValidationParameters validationParameters) {
            if (throwErrorOnLoad)
                throw new Error("Foo");
            if (throwOnLoad)
                throw new IllegalArgumentException("Foo");
            this.modelContext = modelContext;
            return new ModelCreateResult(loadModel(), new ArrayList<>());
        }
    }

}
