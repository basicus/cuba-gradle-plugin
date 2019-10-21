/*
 * Copyright (c) 2008-2019 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.haulmont.gradle.utils;

import org.apache.commons.io.input.BOMInputStream;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

public class JarPropertiesLoader {
    private static final Logger log = LoggerFactory.getLogger(JarPropertiesLoader.class);

    public static InputStream searchForPropertiesInJars(Project project, String propsClasspath) {
        Configuration configuration = project.getConfigurations().getByName("compile");
        ResolvedConfiguration resolvedConf = configuration.getResolvedConfiguration();
        Set<ResolvedDependency> resolvedDependencies = resolvedConf.getFirstLevelModuleDependencies();

        return walkJarDependencies(resolvedDependencies, new HashSet<>(), propsClasspath);
    }

    protected static InputStream walkJarDependencies(Set<ResolvedDependency> dependencies,
                                     Set<ResolvedArtifact> passedArtifacts, String propsClasspath) {
        for (ResolvedDependency dependency : dependencies) {
            walkJarDependencies(dependency.getChildren(), passedArtifacts, propsClasspath);

            for (ResolvedArtifact artifact : dependency.getAllModuleArtifacts()) {
                if (passedArtifacts.contains(artifact)) {
                    continue;
                }

                passedArtifacts.add(artifact);

                if (artifact.getFile().getName().endsWith(".jar")) {
                    if (!checkManifest(artifact)) {
                        continue;
                    }
                    return getPropertiesFromJar(artifact, propsClasspath);
                }
            }
        }
        return null;
    }

    protected static InputStream getPropertiesFromJar(ResolvedArtifact artifact, String propsClasspath) {
        try (JarFile jarFile = new JarFile(artifact.getFile())) {
            ZipEntry propsEntry = jarFile.getEntry(propsClasspath);
            if (propsEntry == null) {
                return null;
            }
            try (BOMInputStream bomInputStream = new BOMInputStream(jarFile.getInputStream(propsEntry))) {
                log.info("Loading app properties from {}", propsClasspath);
                return bomInputStream;
            }
        } catch (IOException e) {
            throw new RuntimeException(String.format("[CubaPlugin] Error occurred during properties searching at %s", artifact.getFile().getAbsolutePath()), e);
        }
    }

    protected static boolean checkManifest(ResolvedArtifact artifact) {
        try (JarFile jarFile = new JarFile(artifact.getFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return false;
            }
        } catch (IOException e) {
            throw new RuntimeException(String.format("[CubaPlugin] Error occurred during properties searching at %s", artifact.getFile().getAbsolutePath()), e);
        }
        return true;
    }
}
