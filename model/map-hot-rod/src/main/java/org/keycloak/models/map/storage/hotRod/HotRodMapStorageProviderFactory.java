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

package org.keycloak.models.map.storage.hotRod;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.common.Profile;
import org.keycloak.component.AmphibianProviderFactory;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.map.client.HotRodAttributeEntity;
import org.keycloak.models.map.client.HotRodClientEntity;
import org.keycloak.models.map.client.HotRodProtocolMapperEntity;
import org.keycloak.models.map.common.HotRodPair;
import org.keycloak.models.map.client.MapClientEntity;
import org.keycloak.models.map.client.MapProtocolMapperEntity;
import org.keycloak.models.map.common.DeepCloner;
import org.keycloak.models.map.common.HotRodEntityDescriptor;
import org.keycloak.models.map.connections.HotRodConnectionProvider;
import org.keycloak.models.map.storage.MapStorageProvider;
import org.keycloak.models.map.storage.MapStorageProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class HotRodMapStorageProviderFactory implements AmphibianProviderFactory<MapStorageProvider>, MapStorageProviderFactory, EnvironmentDependentProviderFactory {

    public static final String PROVIDER_ID = "hotrod";
    private static final Logger LOG = Logger.getLogger(HotRodMapStorageProviderFactory.class);

    private final static DeepCloner CLONER = new DeepCloner.Builder()
            .constructorDC(MapClientEntity.class,         HotRodClientEntity::new)
            .constructor(MapProtocolMapperEntity.class,   HotRodProtocolMapperEntity::new)
            .build();

    public static final Map<Class<?>, HotRodEntityDescriptor<?>> ENTITY_DESCRIPTOR_MAP = new HashMap<>();
    static {
        // Clients descriptor
        ENTITY_DESCRIPTOR_MAP.put(ClientModel.class,
                new HotRodEntityDescriptor<>(ClientModel.class,
                        MapClientEntity.class,
                        Arrays.asList(HotRodClientEntity.class, HotRodAttributeEntity.class, HotRodProtocolMapperEntity.class, HotRodPair.class),
                        "clients"));
    }

    @Override
    public MapStorageProvider create(KeycloakSession session) {
        HotRodConnectionProvider cacheProvider = session.getProvider(HotRodConnectionProvider.class);
        
        if (cacheProvider == null) {
            throw new IllegalStateException("Cannot find HotRodConnectionProvider interface implementation");
        }
        
        return new HotRodMapStorageProvider(this, cacheProvider, CLONER);
    }

    public HotRodEntityDescriptor<?> getEntityDescriptor(Class<?> c) {
        return ENTITY_DESCRIPTOR_MAP.get(c);
    }

    @Override
    public void init(Config.Scope config) {

    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {

    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isSupported() {
        return Profile.isFeatureEnabled(Profile.Feature.MAP_STORAGE);
    }

    @Override
    public String getHelpText() {
        return "HotRod client storage";
    }
}
