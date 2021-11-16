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

package org.keycloak.quarkus.runtime.configuration;

import static org.keycloak.quarkus.runtime.Environment.getProfileOrDefault;
import static org.keycloak.quarkus.runtime.configuration.mappers.PropertyMappers.toCLIFormat;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import io.smallrye.config.ConfigValue;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigProviderResolver;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.keycloak.quarkus.runtime.Environment;
import org.keycloak.quarkus.runtime.configuration.mappers.PropertyMapper;
import org.keycloak.quarkus.runtime.configuration.mappers.PropertyMappers;

/**
 * The entry point for accessing the server configuration
 */
public final class Configuration {

    private static volatile SmallRyeConfig CONFIG;

    private Configuration() {

    }

    public static synchronized SmallRyeConfig getConfig() {
        if (CONFIG == null) {
            CONFIG = (SmallRyeConfig) SmallRyeConfigProviderResolver.instance().getConfig();
        }
        return CONFIG;
    }

    public static Optional<String> getBuiltTimeProperty(String name) {
        String value = KeycloakConfigSourceProvider.PERSISTED_CONFIG_SOURCE.getValue(name);

        if (value == null) {
            value = KeycloakConfigSourceProvider.PERSISTED_CONFIG_SOURCE.getValue(getMappedPropertyName(name));
        }

        if (value == null) {
            String profile = Environment.getProfile();

            if (profile == null) {
                profile = getConfig().getRawValue(Environment.PROFILE);
            }

            value = KeycloakConfigSourceProvider.PERSISTED_CONFIG_SOURCE.getValue("%" + profile + "." + name);
        }

        return Optional.ofNullable(value);
    }

    public static String getRawValue(String propertyName) {
        return getConfig().getRawValue(propertyName);
    }

    public static Iterable<String> getPropertyNames() {
        return getConfig().getPropertyNames();
    }

    public static ConfigValue getConfigValue(String propertyName) {
        return getConfig().getConfigValue(propertyName);
    }

    public static Optional<String> getOptionalValue(String name) {
        return getConfig().getOptionalValue(name, String.class);
    }

    public static Optional<Boolean> getOptionalBooleanValue(String name) {
        return getConfig().getOptionalValue(name, String.class).map(new Function<String, Boolean>() {
            @Override
            public Boolean apply(String s) {
                return Boolean.parseBoolean(s);
            }
        });
    }

    public static String getMappedPropertyName(String key) {
        for (PropertyMapper mapper : PropertyMappers.getMappers()) {
            String mappedProperty = mapper.getFrom();
            List<String> expectedFormats = Arrays.asList(mappedProperty, toCLIFormat(mappedProperty), mappedProperty.toUpperCase().replace('.', '_').replace('-', '_'));

            if (expectedFormats.contains(key)) {
                // we also need to make sure the target property is available when defined such as when defining alias for provider config (no spi-prefix).
                return mapper.getTo() == null ? mappedProperty : mapper.getTo();
            }
        }

        return key;
    }

    public static Optional<String> getRuntimeProperty(String name) {
        for (ConfigSource configSource : getConfig().getConfigSources()) {
            if (PersistedConfigSource.NAME.equals(configSource.getName())) {
                continue;
            }

            String value = getValue(configSource, name);

            if (value == null) {
                value = getValue(configSource, getMappedPropertyName(name));
            }

            if (value != null) {
                return Optional.of(value);
            }
        }

        return Optional.empty();
    }

    private static String getValue(ConfigSource configSource, String name) {
        String value = configSource.getValue(name);

        if (value == null) {
            value = configSource.getValue("%".concat(getProfileOrDefault("prod").concat(".").concat(name)));
        }

        return value;
    }
}
