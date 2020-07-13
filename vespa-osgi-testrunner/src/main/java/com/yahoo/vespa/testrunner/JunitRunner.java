// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.testrunner;

import ai.vespa.hosted.api.TestDescriptor;
import ai.vespa.hosted.cd.internal.TestRuntimeProvider;
import com.google.inject.Inject;
import com.yahoo.component.AbstractComponent;
import com.yahoo.io.IOUtils;
import com.yahoo.jdisc.application.OsgiFramework;
import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.testrunner.legacy.LegacyTestRunner;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.LoggingListener;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author mortent
 */
public class JunitRunner extends AbstractComponent {
    private static final Logger logger = Logger.getLogger(JunitRunner.class.getName());

    private final BundleContext bundleContext;
    private final TestRuntimeProvider testRuntimeProvider;
    private volatile Future<TestReport> execution;

    @Inject
    public JunitRunner(OsgiFramework osgiFramework,
                       JunitTestRunnerConfig config,
                       TestRuntimeProvider testRuntimeProvider) {
        this.testRuntimeProvider = testRuntimeProvider;
        this.bundleContext = getUnrestrictedBundleContext(osgiFramework);
        uglyHackSetCredentialsRootSystemProperty(config);
    }

    // Hack to retrieve bundle context that allows access to other bundles
    private static BundleContext getUnrestrictedBundleContext(OsgiFramework framework) {
        try {
            BundleContext restrictedBundleContext = framework.bundleContext();
            var field = restrictedBundleContext.getClass().getDeclaredField("wrapped");
            field.setAccessible(true);
            return  (BundleContext) field.get(restrictedBundleContext);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // TODO(bjorncs|tokle) Propagate credentials root without system property. Ideally move knowledge about path to test-runtime implementations
    private static void uglyHackSetCredentialsRootSystemProperty(JunitTestRunnerConfig config) {
        String credentialsRoot;
        if (config.useAthenzCredentials()) {
            credentialsRoot = Defaults.getDefaults().underVespaHome("var/vespa/sia");
        } else {
            credentialsRoot = config.artifactsPath().toString();
        }
        System.setProperty("vespa.test.credentials.root", credentialsRoot);
    }

    public void executeTests(TestDescriptor.TestCategory category, byte[] testConfig) {
        if (execution != null && !execution.isDone()) {
            throw new IllegalStateException("Test execution already in progress");
        }
        testRuntimeProvider.initialize(testConfig);
        Optional<Bundle> testBundle = findTestBundle();
        if (testBundle.isEmpty()) {
            throw new RuntimeException("No test bundle available");
        }

        Optional<TestDescriptor> testDescriptor = loadTestDescriptor(testBundle.get());
        if (testDescriptor.isEmpty()) {
            throw new RuntimeException("Could not find test descriptor");
        }
        List<Class<?>> testClasses = loadClasses(testBundle.get(), testDescriptor.get(), category);

        execution =  CompletableFuture.supplyAsync(() -> launchJunit(testClasses));
    }

    public boolean isSupported() {
        return findTestBundle().isPresent();
    }

    private Optional<Bundle> findTestBundle() {
        return Stream.of(bundleContext.getBundles())
                .filter(this::isTestBundle)
                .findAny();
    }

    private boolean isTestBundle(Bundle bundle) {
        var testBundleHeader = bundle.getHeaders().get("X-JDisc-Test-Bundle-Version");
        return testBundleHeader != null && !testBundleHeader.isBlank();
    }

    private Optional<TestDescriptor> loadTestDescriptor(Bundle bundle) {
        URL resource = bundle.getEntry(TestDescriptor.DEFAULT_FILENAME);
        TestDescriptor testDescriptor;
        try {
            var jsonDescriptor = IOUtils.readAll(resource.openStream(), Charset.defaultCharset()).trim();
            testDescriptor = TestDescriptor.fromJsonString(jsonDescriptor);
            logger.info( "Test classes in bundle :" + testDescriptor.toString());
            return Optional.of(testDescriptor);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private List<Class<?>> loadClasses(Bundle bundle, TestDescriptor testDescriptor, TestDescriptor.TestCategory testCategory) {
        List<Class<?>> testClasses = testDescriptor.getConfiguredTests(testCategory).stream()
                .map(className -> loadClass(bundle, className))
                .collect(Collectors.toList());

        StringBuffer buffer = new StringBuffer();
        testClasses.forEach(cl -> buffer.append("\t").append(cl.toString()).append(" / ").append(cl.getClassLoader().toString()).append("\n"));
        logger.info("Loaded testClasses: \n" + buffer.toString());
        return testClasses;
    }

    private Class<?> loadClass(Bundle bundle, String className) {
        try {
            return bundle.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Could not find class: " + className + " in bundle " + bundle.getSymbolicName(), e);
        }
    }

    private TestReport launchJunit(List<Class<?>> testClasses) {
        LauncherDiscoveryRequest discoveryRequest = LauncherDiscoveryRequestBuilder.request()
                .selectors(
                        testClasses.stream().map(DiscoverySelectors::selectClass).collect(Collectors.toList())
                )
                .build();

        var launcherConfig = LauncherConfig.builder()
                .addTestEngines(new JupiterTestEngine())

                .build();
        Launcher launcher = LauncherFactory.create(launcherConfig);

        // Create log listener:
        var logLines = new ArrayList<String>();
        var logListener = LoggingListener.forBiConsumer((t, m) -> log(logLines, m.get(), t));
        // Create a summary listener:
        var summaryListener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(logListener, summaryListener);

        // Execute request
        launcher.execute(discoveryRequest);
        var report = summaryListener.getSummary();
        return new TestReport(report, logLines);
    }

    private void log(List<String> logs, String message, Throwable t) {
        logs.add(message);
        if(t != null) {
            logs.add(t.getMessage());
            List.of(t.getStackTrace()).stream()
                    .map(StackTraceElement::toString)
                    .forEach(logs::add);
        }
    }

    @Override
    public void deconstruct() {
        super.deconstruct();
    }

    public LegacyTestRunner.Status getStatus() {
        if (execution == null) return LegacyTestRunner.Status.NOT_STARTED;
        if (!execution.isDone()) return LegacyTestRunner.Status.RUNNING;
        try {
            TestReport report = execution.get();
            if (report.isSuccess()) {
                return LegacyTestRunner.Status.SUCCESS;
            } else {
                return LegacyTestRunner.Status.FAILURE;
            }
        } catch (InterruptedException|ExecutionException e) {
            logger.log(Level.WARNING, "Error while getting test report", e);
            return LegacyTestRunner.Status.ERROR;
        }
    }

    public String getReportAsJson() {
        if (execution.isDone()) {
            try {
                return execution.get().toJson();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error getting test report", e);
                return "";
            }
        } else {
            return "";
        }
    }
}
