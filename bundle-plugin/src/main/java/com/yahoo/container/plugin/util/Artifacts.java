// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.plugin.util;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author Tony Vaagenes
 * @author ollivir
 */
public class Artifacts {
    interface ScopeTranslator {
        String scopeOf(Artifact artifact);
    }

    private static class NoopScopeTranslator implements ScopeTranslator {
        @Override public String scopeOf(Artifact artifact) { return artifact.getScope(); }
    }

    public static class ArtifactSet {

        private final List<Artifact> jarArtifactsToInclude;
        private final List<Artifact> jarArtifactsProvided;
        private final List<Artifact> nonJarArtifacts;

        private ArtifactSet(
                List<Artifact> jarArtifactsToInclude,
                List<Artifact> jarArtifactsProvided,
                List<Artifact> nonJarArtifacts) {
            this.jarArtifactsToInclude = jarArtifactsToInclude;
            this.jarArtifactsProvided = jarArtifactsProvided;
            this.nonJarArtifacts = nonJarArtifacts;
        }

        public List<Artifact> getJarArtifactsToInclude() {
            return jarArtifactsToInclude;
        }

        public List<Artifact> getJarArtifactsProvided() {
            return jarArtifactsProvided;
        }

        public List<Artifact> getNonJarArtifacts() {
            return nonJarArtifacts;
        }
    }

    public static ArtifactSet getArtifacts(MavenProject project) { return getArtifacts(project, new NoopScopeTranslator()); }

    public static ArtifactSet getArtifacts(MavenProject project, ScopeTranslator scopeTranslator) {
        List<Artifact> jarArtifactsToInclude = new ArrayList<>();
        List<Artifact> jarArtifactsProvided = new ArrayList<>();
        List<Artifact> nonJarArtifactsToInclude = new ArrayList<>();
        List<Artifact> nonJarArtifactsProvided = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            String scope = scopeTranslator.scopeOf(artifact);
            if ("jar".equals(artifact.getType())) {
                if (Artifact.SCOPE_COMPILE.equals(scope)) {
                    jarArtifactsToInclude.add(artifact);
                } else if (Artifact.SCOPE_PROVIDED.equals(scope)) {
                    jarArtifactsProvided.add(artifact);
                }
            } else {
                if (Artifact.SCOPE_COMPILE.equals(scope)) {
                    nonJarArtifactsToInclude.add(artifact);
                } else if (Artifact.SCOPE_PROVIDED.equals(scope)) {
                    nonJarArtifactsProvided.add(artifact);
                }
            }
        }
        nonJarArtifactsToInclude.addAll(nonJarArtifactsProvided);
        return new ArtifactSet(jarArtifactsToInclude, jarArtifactsProvided, nonJarArtifactsToInclude);
    }

    public static Collection<Artifact> getArtifactsToInclude(MavenProject project) {
        return getArtifacts(project, new NoopScopeTranslator()).getJarArtifactsToInclude();
    }
}
