/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pulsar.functions.runtime;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import org.apache.pulsar.functions.auth.FunctionAuthProvider;
import org.apache.pulsar.functions.auth.KubernetesFunctionAuthProvider;
import org.apache.pulsar.functions.instance.AuthenticationConfig;
import org.apache.pulsar.functions.instance.InstanceConfig;
import org.apache.pulsar.functions.secretsproviderconfigurator.SecretsProviderConfigurator;
import org.apache.pulsar.functions.utils.functioncache.FunctionCacheEntry;

import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Consumer;

import static org.apache.pulsar.functions.auth.FunctionAuthUtils.getFunctionAuthData;

/**
 * Thread based function container factory implementation.
 */
@Slf4j
public class ProcessRuntimeFactory implements RuntimeFactory {

    private final String pulsarServiceUrl;
    private final String stateStorageServiceUrl;
    private final boolean authenticationEnabled;
    private AuthenticationConfig authConfig;
    private SecretsProviderConfigurator secretsProviderConfigurator;
    private String javaInstanceJarFile;
    private String pythonInstanceFile;
    private String logDirectory;
    private String extraDependenciesDir;
    private Optional<FunctionAuthProvider> authProvider;

    @VisibleForTesting
    public ProcessRuntimeFactory(String pulsarServiceUrl,
                                 String stateStorageServiceUrl,
                                 AuthenticationConfig authConfig,
                                 String javaInstanceJarFile,
                                 String pythonInstanceFile,
                                 String logDirectory,
                                 String extraDependenciesDir,
                                 SecretsProviderConfigurator secretsProviderConfigurator,
                                 boolean authenticationEnabled,
                                 Optional<FunctionAuthProvider> functionAuthProvider) {
        this.pulsarServiceUrl = pulsarServiceUrl;
        this.stateStorageServiceUrl = stateStorageServiceUrl;
        this.authConfig = authConfig;
        this.secretsProviderConfigurator = secretsProviderConfigurator;
        this.javaInstanceJarFile = javaInstanceJarFile;
        this.pythonInstanceFile = pythonInstanceFile;
        this.extraDependenciesDir = extraDependenciesDir;
        this.logDirectory = logDirectory;
        this.authenticationEnabled = authenticationEnabled;

        // if things are not specified, try to figure out by env properties
        if (this.javaInstanceJarFile == null) {
            String envJavaInstanceJarLocation = System.getProperty(FunctionCacheEntry.JAVA_INSTANCE_JAR_PROPERTY);
            if (null != envJavaInstanceJarLocation) {
                log.info("Java instance jar location is not defined,"
                        + " using the location defined in system environment : {}", envJavaInstanceJarLocation);
                this.javaInstanceJarFile = envJavaInstanceJarLocation;
            } else {
                throw new RuntimeException("No JavaInstanceJar specified");
            }
        }

        if (this.pythonInstanceFile == null) {
            String envPythonInstanceLocation = System.getProperty("pulsar.functions.python.instance.file");
            if (null != envPythonInstanceLocation) {
                log.info("Python instance file location is not defined"
                        + " using the location defined in system environment : {}", envPythonInstanceLocation);
                this.pythonInstanceFile = envPythonInstanceLocation;
            } else {
                throw new RuntimeException("No PythonInstanceFile specified");
            }
        }

        if (this.logDirectory == null) {
            String envProcessContainerLogDirectory = System.getProperty("pulsar.functions.process.container.log.dir");
            if (null != envProcessContainerLogDirectory) {
                this.logDirectory = envProcessContainerLogDirectory;
            } else {
                // use a default location
                this.logDirectory = Paths.get("logs").toFile().getAbsolutePath();
            }
        }
        this.logDirectory = this.logDirectory + "/functions";

        if (this.extraDependenciesDir == null) {
            String envProcessContainerExtraDependenciesDir =
                System.getProperty("pulsar.functions.extra.dependencies.dir");
            if (null != envProcessContainerExtraDependenciesDir) {
                log.info("Extra dependencies location is not defined using"
                    + " the location defined in system environment : {}", envProcessContainerExtraDependenciesDir);
                this.extraDependenciesDir = envProcessContainerExtraDependenciesDir;
            } else {
                log.info("No extra dependencies location is defined in either"
                    + " function worker config or system environment");
            }
        }

        authProvider = functionAuthProvider;
    }

    @Override
    public ProcessRuntime createContainer(InstanceConfig instanceConfig, String codeFile,
                                          String originalCodeFileName,
                                          Long expectedHealthCheckInterval) throws Exception {
        String instanceFile = null;
        switch (instanceConfig.getFunctionDetails().getRuntime()) {
            case JAVA:
                instanceFile = javaInstanceJarFile;
                break;
            case PYTHON:
                instanceFile = pythonInstanceFile;
                break;
            case GO:
                break;
            default:
                throw new RuntimeException("Unsupported Runtime " + instanceConfig.getFunctionDetails().getRuntime());
        }

        // configure auth if necessary
        if (authenticationEnabled) {
            authProvider.ifPresent(functionAuthProvider -> functionAuthProvider.configureAuthenticationConfig(authConfig,
                    Optional.ofNullable(getFunctionAuthData(Optional.ofNullable(instanceConfig.getFunctionAuthenticationSpec())))));
        }

        return new ProcessRuntime(
            instanceConfig,
            instanceFile,
            extraDependenciesDir,
            logDirectory,
            codeFile,
            pulsarServiceUrl,
            stateStorageServiceUrl,
            authConfig,
            secretsProviderConfigurator,
            expectedHealthCheckInterval);
    }

    @Override
    public Optional<FunctionAuthProvider> getAuthProvider() {
        return authProvider;
    }

    @Override
    public void close() {
    }
}
