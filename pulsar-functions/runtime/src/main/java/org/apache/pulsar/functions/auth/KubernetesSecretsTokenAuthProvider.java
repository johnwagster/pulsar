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
package org.apache.pulsar.functions.auth;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1PodSpec;
import io.kubernetes.client.models.V1Secret;
import io.kubernetes.client.models.V1SecretVolumeSource;
import io.kubernetes.client.models.V1StatefulSet;
import io.kubernetes.client.models.V1Volume;
import io.kubernetes.client.models.V1VolumeMount;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.pulsar.broker.authentication.AuthenticationDataSource;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.auth.AuthenticationToken;
import org.apache.pulsar.functions.instance.AuthenticationConfig;
import org.apache.pulsar.functions.utils.Actions;
import org.apache.pulsar.functions.utils.FunctionCommon;

import javax.naming.AuthenticationException;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.net.HttpURLConnection.HTTP_CONFLICT;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.pulsar.broker.authentication.AuthenticationProviderToken.getToken;

@Slf4j
public class KubernetesSecretsTokenAuthProvider implements KubernetesFunctionAuthProvider {

    private static final int NUM_RETRIES = 5;
    private static final long SLEEP_BETWEEN_RETRIES_MS = 500;
    private static final String SECRET_NAME = "function-auth";
    private static final String DEFAULT_SECRET_MOUNT_DIR = "/etc/auth";
    private static final String FUNCTION_AUTH_TOKEN = "token";

    private CoreV1Api coreClient;
    private String kubeNamespace;

    @Override
    public void initialize(CoreV1Api coreClient, String kubeNamespace) {
        this.coreClient = coreClient;
        this.kubeNamespace = kubeNamespace;
    }

    @Override
    public void configureAuthDataStatefulSet(V1StatefulSet statefulSet, Optional<FunctionAuthData> functionAuthData) {
        if (!functionAuthData.isPresent()) {
            return;
        }

        V1PodSpec podSpec = statefulSet.getSpec().getTemplate().getSpec();

        // configure pod mount secret with auth token
        podSpec.setVolumes(Collections.singletonList(
                new V1Volume()
                        .name(SECRET_NAME)
                        .secret(
                                new V1SecretVolumeSource()
                                        .secretName(getSecretName(new String(functionAuthData.get().getData())))
                                        .defaultMode(256))));

        podSpec.getContainers().forEach(container -> container.setVolumeMounts(Collections.singletonList(
                new V1VolumeMount()
                        .name(SECRET_NAME)
                        .mountPath(DEFAULT_SECRET_MOUNT_DIR)
                        .readOnly(true))));

    }

    @Override
    public void configureAuthenticationConfig(AuthenticationConfig authConfig, Optional<FunctionAuthData> functionAuthData) {
        if (!functionAuthData.isPresent()) {
            // if auth data is not present maybe user is trying to use anonymous role thus don't pass in any auth config
            authConfig.setClientAuthenticationPlugin(null);
            authConfig.setClientAuthenticationParameters(null);
        } else {
            authConfig.setClientAuthenticationPlugin(AuthenticationToken.class.getName());
            authConfig.setClientAuthenticationParameters(String.format("file://%s/%s", DEFAULT_SECRET_MOUNT_DIR, FUNCTION_AUTH_TOKEN));
        }
    }


    @Override
    public Optional<FunctionAuthData> cacheAuthData(String tenant, String namespace, String name,
                                                    AuthenticationDataSource authenticationDataSource) {
        String id = null;
        try {
            String token = getToken(authenticationDataSource);
            if (token != null) {
                id = createSecret(token, tenant, namespace, name);
            }
        } catch (Exception e) {
            log.warn("Failed to get token for function {}", FunctionCommon.getFullyQualifiedName(tenant, namespace, name), e);
            // ignore exception and continue since anonymous user might to used
        }

        if (id != null) {
            return Optional.of(FunctionAuthData.builder().data(id.getBytes()).build());
        }
        return Optional.empty();
    }

    @Override
    public void cleanUpAuthData(String tenant, String namespace, String name, Optional<FunctionAuthData> functionAuthData) throws Exception {
        if (!functionAuthData.isPresent()) {
            return;
        }

        String fqfn = FunctionCommon.getFullyQualifiedName(tenant, namespace, name);

        String secretId = new String(functionAuthData.get().getData());
        // Make sure secretName is empty.  Defensive programing
        if (isBlank(secretId)) {
            log.warn("Secret name for function {} is empty.", fqfn);
            return;
        }

        String secretName = getSecretName(secretId);

        Actions.Action deleteSecrets = Actions.Action.builder()
                .actionName(String.format("Deleting secrets for function %s", fqfn))
                .numRetries(NUM_RETRIES)
                .sleepBetweenInvocationsMs(SLEEP_BETWEEN_RETRIES_MS)
                .supplier(() -> {
                    try {
                        V1DeleteOptions v1DeleteOptions = new V1DeleteOptions();
                        v1DeleteOptions.setGracePeriodSeconds(0L);
                        v1DeleteOptions.setPropagationPolicy("Foreground");

                        // make sure secretName is not null or empty string.
                        // If deleteNamespacedSecret is called and secret name is null or empty string
                        // it will delete all the secrets in the namespace
                        coreClient.deleteNamespacedSecret(secretName,
                                kubeNamespace, v1DeleteOptions, "true",
                                null, null, null);
                    } catch (ApiException e) {
                        // if already deleted
                        if (e.getCode() == HTTP_NOT_FOUND) {
                            log.warn("Secrets for function {} does not exist", fqfn);
                            return Actions.ActionResult.builder().success(true).build();
                        }

                        String errorMsg = e.getResponseBody() != null ? e.getResponseBody() : e.getMessage();
                        return Actions.ActionResult.builder()
                                .success(false)
                                .errorMsg(errorMsg)
                                .build();
                    }
                    return Actions.ActionResult.builder().success(true).build();
                })
                .build();

        Actions.Action waitForSecretsDeletion = Actions.Action.builder()
                .actionName(String.format("Waiting for secrets for function %s to complete deletion", fqfn))
                .numRetries(NUM_RETRIES)
                .sleepBetweenInvocationsMs(SLEEP_BETWEEN_RETRIES_MS)
                .supplier(() -> {
                    try {
                        coreClient.readNamespacedSecret(secretName, kubeNamespace,
                                null, null, null);

                    } catch (ApiException e) {
                        // statefulset is gone
                        if (e.getCode() == HTTP_NOT_FOUND) {
                            return Actions.ActionResult.builder().success(true).build();
                        }
                        String errorMsg = e.getResponseBody() != null ? e.getResponseBody() : e.getMessage();
                        return Actions.ActionResult.builder()
                                .success(false)
                                .errorMsg(errorMsg)
                                .build();
                    }
                    return Actions.ActionResult.builder()
                            .success(false)
                            .build();
                })
                .build();

        AtomicBoolean success = new AtomicBoolean(false);
        Actions.newBuilder()
                .addAction(deleteSecrets.toBuilder()
                        .continueOn(true)
                        .build())
                .addAction(waitForSecretsDeletion.toBuilder()
                        .continueOn(false)
                        .onSuccess(ignore -> success.set(true))
                        .build())
                .addAction(deleteSecrets.toBuilder()
                        .continueOn(true)
                        .build())
                .addAction(waitForSecretsDeletion.toBuilder()
                        .onSuccess(ignore -> success.set(true))
                        .build())
                .run();

        if (!success.get()) {
            throw new RuntimeException(String.format("Failed to delete secrets for function %s", fqfn));
        }
    }

    @Override
    public Optional<FunctionAuthData> updateAuthData(String tenant, String namespace, String name,
                                                     Optional<FunctionAuthData> existingFunctionAuthData,
                                                     AuthenticationDataSource authenticationDataSource) throws Exception {

        String secretId;
        if (existingFunctionAuthData.isPresent()) {
            secretId = new String(existingFunctionAuthData.get().getData());
        } else {
            secretId = RandomStringUtils.random(5, true, true).toLowerCase();
        }

        String token;
        try {
            token = getToken(authenticationDataSource);
        } catch (AuthenticationException e) {
             // No token is passed so delete the token. Might be trying to switch over to using anonymous user
            cleanUpAuthData(
                    tenant, namespace, name,
                    existingFunctionAuthData);
            return Optional.empty();
        }

        if (token != null) {
            upsertSecret(token, tenant, namespace, name, getSecretName(secretId));
            return Optional.of(FunctionAuthData.builder().data(secretId.getBytes()).build());
        }

        return existingFunctionAuthData;
    }

    private void upsertSecret(String token, String tenant, String namespace, String name, String secretName) throws InterruptedException {

        Actions.Action createAuthSecret = Actions.Action.builder()
                .actionName(String.format("Upsert authentication secret for function %s/%s/%s", tenant, namespace, name))
                .numRetries(NUM_RETRIES)
                .sleepBetweenInvocationsMs(SLEEP_BETWEEN_RETRIES_MS)
                .supplier(() -> {
                    String id =  RandomStringUtils.random(5, true, true).toLowerCase();
                    V1Secret v1Secret = new V1Secret()
                            .metadata(new V1ObjectMeta().name(secretName))
                            .data(Collections.singletonMap(FUNCTION_AUTH_TOKEN, token.getBytes()));

                    try {
                        coreClient.createNamespacedSecret(kubeNamespace, v1Secret, null);
                    } catch (ApiException e) {
                        if (e.getCode() == HTTP_CONFLICT) {
                            try {
                                coreClient.replaceNamespacedSecret(secretName, kubeNamespace, v1Secret, null);
                                return Actions.ActionResult.builder().success(true).build();

                            } catch (ApiException e1) {
                                String errorMsg = e.getResponseBody() != null ? e.getResponseBody() : e.getMessage();
                                return Actions.ActionResult.builder()
                                        .success(false)
                                        .errorMsg(errorMsg)
                                        .build();
                            }
                        }

                        String errorMsg = e.getResponseBody() != null ? e.getResponseBody() : e.getMessage();
                        return Actions.ActionResult.builder()
                                .success(false)
                                .errorMsg(errorMsg)
                                .build();
                    }

                    return Actions.ActionResult.builder().success(true).build();
                })
                .build();

        AtomicBoolean success = new AtomicBoolean(false);
        Actions.newBuilder()
                .addAction(createAuthSecret.toBuilder()
                        .onSuccess(ignore -> success.set(true))
                        .build())
                .run();

        if (!success.get()) {
            throw new RuntimeException(String.format("Failed to upsert authentication secret for function %s/%s/%s", tenant, namespace, name));
        }
    }

    private String createSecret(String token, String tenant, String namespace, String name) throws ApiException, InterruptedException {

        StringBuilder sb = new StringBuilder();
        Actions.Action createAuthSecret = Actions.Action.builder()
                .actionName(String.format("Creating authentication secret for function %s/%s/%s", tenant, namespace, name))
                .numRetries(NUM_RETRIES)
                .sleepBetweenInvocationsMs(SLEEP_BETWEEN_RETRIES_MS)
                .supplier(() -> {
                    String id =  RandomStringUtils.random(5, true, true).toLowerCase();
                    V1Secret v1Secret = new V1Secret()
                            .metadata(new V1ObjectMeta().name(getSecretName(id)))
                            .data(Collections.singletonMap(FUNCTION_AUTH_TOKEN, token.getBytes()));
                    try {
                        coreClient.createNamespacedSecret(kubeNamespace, v1Secret, "true");
                    } catch (ApiException e) {
                        // already exists
                        if (e.getCode() == HTTP_CONFLICT) {
                            return Actions.ActionResult.builder()
                                    .errorMsg(String.format("Secret %s already present", id))
                                    .success(false)
                                    .build();
                        }

                        String errorMsg = e.getResponseBody() != null ? e.getResponseBody() : e.getMessage();
                        return Actions.ActionResult.builder()
                                .success(false)
                                .errorMsg(errorMsg)
                                .build();
                    }

                    sb.append(id.toCharArray());
                    return Actions.ActionResult.builder().success(true).build();
                })
                .build();

        AtomicBoolean success = new AtomicBoolean(false);
        Actions.newBuilder()
                .addAction(createAuthSecret.toBuilder()
                        .onSuccess(ignore -> success.set(true))
                        .build())
                .run();

        if (!success.get()) {
            throw new RuntimeException(String.format("Failed to create authentication secret for function %s/%s/%s", tenant, namespace, name));
        }

        return sb.toString();
    }

    private String getSecretName(String id) {
        return "pf-secret-" + id;
    }
}
