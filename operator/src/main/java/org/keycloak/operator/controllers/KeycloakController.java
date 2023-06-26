/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.operator.controllers;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.config.informer.InformerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusHandler;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.Mappers;
import io.quarkus.logging.Log;
import org.keycloak.operator.Config;
import org.keycloak.operator.Constants;
import org.keycloak.operator.crds.v2alpha1.deployment.Keycloak;
import org.keycloak.operator.crds.v2alpha1.deployment.KeycloakStatus;
import org.keycloak.operator.crds.v2alpha1.deployment.KeycloakStatusAggregator;
import org.keycloak.operator.crds.v2alpha1.deployment.KeycloakStatusCondition;

import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.javaoperatorsdk.operator.api.reconciler.Constants.WATCH_CURRENT_NAMESPACE;

@ControllerConfiguration(namespaces = WATCH_CURRENT_NAMESPACE)
public class KeycloakController implements Reconciler<Keycloak>, EventSourceInitializer<Keycloak>, ErrorStatusHandler<Keycloak> {

    @Inject
    KubernetesClient client;

    @Inject
    Config config;

    @Override
    public Map<String, EventSource> prepareEventSources(EventSourceContext<Keycloak> context) {
        String namespace = context.getControllerConfiguration().getConfigurationService().getClientConfiguration().getNamespace();

        InformerConfiguration<StatefulSet> statefulSetIC = InformerConfiguration
                .from(StatefulSet.class)
                .withLabelSelector(Constants.DEFAULT_LABELS_AS_STRING)
                .withNamespaces(namespace)
                .withSecondaryToPrimaryMapper(Mappers.fromOwnerReference())
                .withOnUpdateFilter(new MetadataAwareOnUpdateFilter<>())
                .build();

        InformerConfiguration<Service> servicesIC = InformerConfiguration
                .from(Service.class)
                .withLabelSelector(Constants.DEFAULT_LABELS_AS_STRING)
                .withNamespaces(namespace)
                .withSecondaryToPrimaryMapper(Mappers.fromOwnerReference())
                .withOnUpdateFilter(new MetadataAwareOnUpdateFilter<>())
                .build();

        InformerConfiguration<Ingress> ingressesIC = InformerConfiguration
                .from(Ingress.class)
                .withLabelSelector(Constants.DEFAULT_LABELS_AS_STRING)
                .withNamespaces(namespace)
                .withSecondaryToPrimaryMapper(Mappers.fromOwnerReference())
                .withOnUpdateFilter(new MetadataAwareOnUpdateFilter<>())
                .build();

        EventSource statefulSetEvent = new InformerEventSource<>(statefulSetIC, context);
        EventSource servicesEvent = new InformerEventSource<>(servicesIC, context);
        EventSource ingressesEvent = new InformerEventSource<>(ingressesIC, context);

        return EventSourceInitializer.nameEventSources(statefulSetEvent,
                servicesEvent,
                ingressesEvent,
                WatchedSecretsStore.getStoreEventSource(client, namespace),
                WatchedSecretsStore.getWatchedSecretsEventSource(client, namespace));
    }

    @Override
    public UpdateControl<Keycloak> reconcile(Keycloak kc, Context<Keycloak> context) {
        String kcName = kc.getMetadata().getName();
        String namespace = kc.getMetadata().getNamespace();

        Log.infof("--- Reconciling Keycloak: %s in namespace: %s", kcName, namespace);

        var statusAggregator = new KeycloakStatusAggregator(kc.getStatus(), kc.getMetadata().getGeneration());

        var kcAdminSecret = new KeycloakAdminSecret(client, kc);
        kcAdminSecret.createOrUpdateReconciled();

        var kcDeployment = new KeycloakDeployment(client, config, kc, context.getSecondaryResource(StatefulSet.class).orElse(null), kcAdminSecret.getName());
        var watchedSecrets = new WatchedSecretsStore(kcDeployment.getConfigSecretsNames(), client, kc);
        kcDeployment.createOrUpdateReconciled();
        if (watchedSecrets.changesDetected()) {
            Log.info("Config Secrets modified, restarting deployment");
            kcDeployment.rollingRestart();
        }
        kcDeployment.updateStatus(statusAggregator);
        watchedSecrets.createOrUpdateReconciled();

        var kcService = new KeycloakService(client, kc);
        kcService.updateStatus(statusAggregator);
        kcService.createOrUpdateReconciled();
        var kcDiscoveryService = new KeycloakDiscoveryService(client, kc);
        kcDiscoveryService.updateStatus(statusAggregator);
        kcDiscoveryService.createOrUpdateReconciled();

        var kcIngress = new KeycloakIngress(client, kc);
        kcIngress.updateStatus(statusAggregator);
        kcIngress.createOrUpdateReconciled();

        var status = statusAggregator.build();

        Log.info("--- Reconciliation finished successfully");

        UpdateControl<Keycloak> updateControl;
        if (status.equals(kc.getStatus())) {
            updateControl = UpdateControl.noUpdate();
        }
        else {
            kc.setStatus(status);
            updateControl = UpdateControl.updateStatus(kc);
        }

        if (status.findCondition(KeycloakStatusCondition.READY)
                .filter(c -> !Boolean.TRUE.equals(c.getStatus())).isPresent()) {
            updateControl.rescheduleAfter(10, TimeUnit.SECONDS);
        }

        return updateControl;
    }

    @Override
    public ErrorStatusUpdateControl<Keycloak> updateErrorStatus(Keycloak kc, Context<Keycloak> context, Exception e) {
        Log.error("--- Error reconciling", e);
        KeycloakStatus status = new KeycloakStatusAggregator(kc.getStatus(), kc.getMetadata().getGeneration())
                .addErrorMessage("Error performing operations:\n" + e.getMessage())
                .build();

        kc.setStatus(status);

        return ErrorStatusUpdateControl.updateStatus(kc);
    }
}
