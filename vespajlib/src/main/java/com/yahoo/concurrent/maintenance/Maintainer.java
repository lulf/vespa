// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.concurrent.maintenance;

import com.google.common.util.concurrent.UncheckedTimeoutException;
import com.yahoo.net.HostName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The base class for maintainers. A maintainer is some job which runs at a fixed rate to perform maintenance tasks.
 *
 * @author bratseth
 * @author mpolden
 */
public abstract class Maintainer implements Runnable, AutoCloseable {

    protected final Logger log = Logger.getLogger(this.getClass().getName());

    private final String name;
    private final JobControl jobControl;
    private final JobMetrics jobMetrics;
    private final Duration interval;
    private final ScheduledExecutorService service;

    public Maintainer(String name, Duration interval, Instant startedAt, JobControl jobControl, JobMetrics jobMetrics, List<String> clusterHostnames) {
        this(name, interval, staggeredDelay(interval, startedAt, HostName.getLocalhost(), clusterHostnames), jobControl, jobMetrics);
    }

    public Maintainer(String name, Duration interval, Duration initialDelay, JobControl jobControl, JobMetrics jobMetrics) {
        this.name = name;
        this.interval = requireInterval(interval);
        this.jobControl = Objects.requireNonNull(jobControl);
        this.jobMetrics = Objects.requireNonNull(jobMetrics);
        service = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, name() + "-worker"));
        service.scheduleAtFixedRate(this, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        jobControl.started(name(), this);
    }

    @Override
    public void run() {
        try {
            if (jobControl.isActive(name())) {
                lockAndMaintain();
            }
        } catch (UncheckedTimeoutException ignored) {
            // Another actor is running this job
        } catch (Throwable e) {
            log.log(Level.WARNING, this + " failed. Will retry in " + interval.toMinutes() + " minutes", e);
        }
    }

    @Override
    public void close() {
        var timeout = Duration.ofSeconds(30);
        service.shutdown();
        try {
            if (!service.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.log(Level.WARNING, "Maintainer " + name() + " failed to shutdown " +
                                       "within " + timeout);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public final String toString() { return name(); }

    /** Called once each time this maintenance job should run. Returns whether the maintenance run was succesful */
    protected abstract boolean maintain();

    /** Returns the interval at which this job is set to run */
    protected Duration interval() { return interval; }

    /** Run this while holding the job lock */
    @SuppressWarnings("unused")
    public final void lockAndMaintain() {
        try (var lock = jobControl.lockJob(name())) {
            try {
                if (maintain()) jobMetrics.recordSuccessOf(name());
            } finally {
                // Always forward metrics
                jobMetrics.forward(name());
            }
        }
    }

    /** Returns the simple name of this job */
    public final String name() {
        return name == null ? this.getClass().getSimpleName() : name;
    }

    /** Returns the initial delay of this calculated from cluster index of given hostname */
    static Duration staggeredDelay(Duration interval, Instant now, String hostname, List<String> clusterHostnames) {
        Objects.requireNonNull(clusterHostnames);
        if ( ! clusterHostnames.contains(hostname))
            return interval;

        long offset = clusterHostnames.indexOf(hostname) * interval.toMillis() / clusterHostnames.size();
        return Duration.ofMillis(Math.floorMod(offset - now.toEpochMilli(), interval.toMillis()));
    }

    private static Duration requireInterval(Duration interval) {
        Objects.requireNonNull(interval);
        if (interval.isNegative() || interval.isZero())
            throw new IllegalArgumentException("Interval must be positive, but was " + interval);
        return interval;
    }

}
