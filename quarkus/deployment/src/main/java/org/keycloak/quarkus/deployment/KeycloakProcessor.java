/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.quarkus.deployment;

import static org.keycloak.quarkus.runtime.configuration.Configuration.getConfigValue;
import static org.keycloak.quarkus.runtime.configuration.Configuration.getPropertyNames;
import static org.keycloak.quarkus.runtime.storage.database.jpa.QuarkusJpaConnectionProviderFactory.QUERY_PROPERTY_PREFIX;
import static org.keycloak.connections.jpa.util.JpaUtils.loadSpecificNamedQueries;
import static org.keycloak.quarkus.runtime.configuration.MicroProfileConfigProvider.NS_KEYCLOAK;
import static org.keycloak.representations.provider.ScriptProviderDescriptor.AUTHENTICATORS;
import static org.keycloak.representations.provider.ScriptProviderDescriptor.MAPPERS;
import static org.keycloak.representations.provider.ScriptProviderDescriptor.POLICIES;
import static org.keycloak.quarkus.runtime.Environment.CLI_ARGS;
import static org.keycloak.quarkus.runtime.Environment.getProviderFiles;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.Entity;
import javax.persistence.spi.PersistenceUnitTransactionType;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import io.quarkus.agroal.spi.JdbcDataSourceBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.StaticInitConfigSourceProviderBuildItem;
import io.quarkus.hibernate.orm.deployment.AdditionalJpaModelBuildItem;
import io.quarkus.hibernate.orm.deployment.HibernateOrmConfig;
import io.quarkus.hibernate.orm.deployment.PersistenceXmlDescriptorBuildItem;
import io.quarkus.resteasy.server.common.deployment.ResteasyDeploymentCustomizerBuildItem;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.smallrye.health.runtime.SmallRyeHealthHandler;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.jpa.boot.internal.ParsedPersistenceXmlDescriptor;
import org.infinispan.commons.util.FileLookupFactory;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.logging.Logger;
import org.jboss.resteasy.plugins.server.servlet.ResteasyContextParameters;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.keycloak.Config;
import org.keycloak.quarkus.runtime.integration.jaxrs.QuarkusKeycloakApplication;
import org.keycloak.authentication.AuthenticatorSpi;
import org.keycloak.authentication.authenticators.browser.DeployedScriptAuthenticatorFactory;
import org.keycloak.authorization.policy.provider.PolicySpi;
import org.keycloak.authorization.policy.provider.js.DeployedScriptPolicyFactory;
import org.keycloak.common.Profile;
import org.keycloak.common.util.StreamUtil;
import org.keycloak.quarkus.runtime.configuration.Configuration;
import org.keycloak.quarkus.runtime.configuration.KeycloakConfigSourceProvider;
import org.keycloak.quarkus.runtime.configuration.MicroProfileConfigProvider;
import org.keycloak.connections.jpa.DefaultJpaConnectionProviderFactory;
import org.keycloak.connections.jpa.updater.liquibase.LiquibaseJpaUpdaterProviderFactory;
import org.keycloak.connections.jpa.updater.liquibase.conn.DefaultLiquibaseConnectionProvider;
import org.keycloak.protocol.ProtocolMapperSpi;
import org.keycloak.protocol.oidc.mappers.DeployedScriptOIDCProtocolMapper;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.KeycloakDeploymentInfo;
import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.ProviderManager;
import org.keycloak.provider.Spi;
import org.keycloak.quarkus.runtime.integration.web.QuarkusRequestFilter;
import org.keycloak.quarkus.runtime.dev.QuarkusDevRequestFilter;
import org.keycloak.quarkus.runtime.KeycloakRecorder;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.vertx.http.deployment.FilterBuildItem;

import org.keycloak.quarkus.runtime.storage.infinispan.CacheInitializer;
import org.keycloak.representations.provider.ScriptProviderDescriptor;
import org.keycloak.representations.provider.ScriptProviderMetadata;
import org.keycloak.quarkus.runtime.integration.web.NotFoundHandler;
import org.keycloak.services.ServicesLogger;
import org.keycloak.quarkus.runtime.services.health.KeycloakMetricsHandler;
import org.keycloak.theme.FolderThemeProviderFactory;
import org.keycloak.transaction.JBossJtaTransactionManagerLookup;
import org.keycloak.quarkus.runtime.Environment;
import org.keycloak.util.JsonSerialization;

class KeycloakProcessor {

    private static final Logger logger = Logger.getLogger(KeycloakProcessor.class);

    private static final String JAR_FILE_SEPARATOR = "!/";
    private static final String DEFAULT_HEALTH_ENDPOINT = "/health";
    private static final Map<String, Function<ScriptProviderMetadata, ProviderFactory>> DEPLOYEABLE_SCRIPT_PROVIDERS = new HashMap<>();
    private static final String KEYCLOAK_SCRIPTS_JSON_PATH = "META-INF/keycloak-scripts.json";

    private static final List<Class<? extends ProviderFactory>> IGNORED_PROVIDER_FACTORY = Arrays.asList(
            JBossJtaTransactionManagerLookup.class,
            DefaultJpaConnectionProviderFactory.class,
            DefaultLiquibaseConnectionProvider.class,
            FolderThemeProviderFactory.class,
            LiquibaseJpaUpdaterProviderFactory.class);

    static {
        DEPLOYEABLE_SCRIPT_PROVIDERS.put(AUTHENTICATORS, KeycloakProcessor::registerScriptAuthenticator);
        DEPLOYEABLE_SCRIPT_PROVIDERS.put(POLICIES, KeycloakProcessor::registerScriptPolicy);
        DEPLOYEABLE_SCRIPT_PROVIDERS.put(MAPPERS, KeycloakProcessor::registerScriptMapper);
    }

    private static ProviderFactory registerScriptAuthenticator(ScriptProviderMetadata metadata) {
        return new DeployedScriptAuthenticatorFactory(metadata);
    }

    private static ProviderFactory registerScriptPolicy(ScriptProviderMetadata metadata) {
        return new DeployedScriptPolicyFactory(metadata);
    }

    private static ProviderFactory registerScriptMapper(ScriptProviderMetadata metadata) {
        return new DeployedScriptOIDCProtocolMapper(metadata);
    }

    @BuildStep
    FeatureBuildItem getFeature() {
        return new FeatureBuildItem("keycloak");
    }

    /**
     * <p>Configures the persistence unit for Quarkus.
     *
     * <p>The main reason we have this build step is because we re-use the same persistence unit from {@code keycloak-model-jpa}
     * module, the same used by the Wildfly distribution. The {@code hibernate-orm} extension expects that the dialect is statically
     * set to the persistence unit if there is any from the classpath and we use this method to obtain the dialect from the configuration
     * file so that we can build the application with whatever dialect we want. In addition to the dialect, we should also be 
     * allowed to set any additional defaults that we think that makes sense.
     *
     * @param config
     * @param descriptors
     */
    @BuildStep
    void configureHibernate(HibernateOrmConfig config,
            List<PersistenceXmlDescriptorBuildItem> descriptors,
            List<JdbcDataSourceBuildItem> jdbcDataSources,
            BuildProducer<AdditionalJpaModelBuildItem> additionalJpaModel,
            CombinedIndexBuildItem indexBuildItem) {
        ParsedPersistenceXmlDescriptor descriptor = descriptors.get(0).getDescriptor();
        configureJpaProperties(descriptor, config, jdbcDataSources);
        configureJpaModel(descriptor, indexBuildItem);
    }

    private void configureJpaProperties(ParsedPersistenceXmlDescriptor descriptor, HibernateOrmConfig config,
            List<JdbcDataSourceBuildItem> jdbcDataSources) {
        Properties unitProperties = descriptor.getProperties();

        unitProperties.setProperty(AvailableSettings.DIALECT, config.defaultPersistenceUnit.dialect.dialect.orElse(null));
        unitProperties.setProperty(AvailableSettings.JPA_TRANSACTION_TYPE, PersistenceUnitTransactionType.JTA.name());
        unitProperties.setProperty(AvailableSettings.QUERY_STARTUP_CHECKING, Boolean.FALSE.toString());

        String dbKind = jdbcDataSources.get(0).getDbKind();

        for (Entry<Object, Object> query : loadSpecificNamedQueries(dbKind.toLowerCase()).entrySet()) {
            unitProperties.setProperty(QUERY_PROPERTY_PREFIX + query.getKey(), query.getValue().toString());
        }
    }

    private void configureJpaModel(ParsedPersistenceXmlDescriptor descriptor, CombinedIndexBuildItem indexBuildItem) {
        IndexView index = indexBuildItem.getIndex();
        Collection<AnnotationInstance> annotations = index.getAnnotations(DotName.createSimple(Entity.class.getName()));

        for (AnnotationInstance annotation : annotations) {
            AnnotationTarget target = annotation.target();
            String targetName = target.asClass().name().toString();

            if (isCustomJpaModel(targetName)) {
                descriptor.addClasses(targetName);
            }
        }
    }

    private boolean isCustomJpaModel(String targetName) {
        return !targetName.startsWith("org.keycloak") || targetName.startsWith("org.keycloak.testsuite");
    }

    /**
     * <p>Load the built-in provider factories during build time so we don't spend time looking up them at runtime. By loading
     * providers at this stage we are also able to perform a more dynamic configuration based on the default providers.
     *
     * <p>User-defined providers are going to be loaded at startup</p>
     *
     * @param recorder
     */
    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    void configureProviders(KeycloakRecorder recorder) {
        Profile.setInstance(recorder.createProfile());
        Map<Spi, Map<Class<? extends Provider>, Map<String, Class<? extends ProviderFactory>>>> factories = new HashMap<>();
        Map<Class<? extends Provider>, String> defaultProviders = new HashMap<>();
        Map<String, ProviderFactory> preConfiguredProviders = new HashMap<>();

        for (Entry<Spi, Map<Class<? extends Provider>, Map<String, ProviderFactory>>> entry : loadFactories(preConfiguredProviders)
                .entrySet()) {
            checkProviders(entry.getKey(), entry.getValue(), defaultProviders);

            for (Entry<Class<? extends Provider>, Map<String, ProviderFactory>> value : entry.getValue().entrySet()) {
                for (ProviderFactory factory : value.getValue().values()) {
                    factories.computeIfAbsent(entry.getKey(),
                            key -> new HashMap<>())
                            .computeIfAbsent(entry.getKey().getProviderClass(), aClass -> new HashMap<>()).put(factory.getId(),factory.getClass());
                }
            }
        }

        recorder.configSessionFactory(factories, defaultProviders, preConfiguredProviders, Environment.isRebuild());
    }

    /**
     * Register the custom {@link org.eclipse.microprofile.config.spi.ConfigSource} implementations.
     *
     * @param configSources
     */
    @BuildStep
    void configureConfigSources(BuildProducer<StaticInitConfigSourceProviderBuildItem> configSources) {
        configSources.produce(new StaticInitConfigSourceProviderBuildItem(KeycloakConfigSourceProvider.class.getName()));
    }

    /**
     * <p>Make the build time configuration available at runtime so that the server can run without having to specify some of
     * the properties again.
     *
     * @param recorder the recorder
     */
    @Record(ExecutionTime.STATIC_INIT)
    @BuildStep(onlyIf = isReAugmentation.class)
    void setBuildTimeProperties(KeycloakRecorder recorder) {
        Properties properties = new Properties();

        for (String name : getPropertyNames()) {
            if (isNotPersistentProperty(name)) {
                continue;
            }

            Optional<String> value = Configuration.getOptionalValue(name);

            if (value.isPresent()) {
                properties.put(name, value.get());
            }
        }

        for (File jar : getProviderFiles().values()) {
            properties.put(String.format("kc.provider.file.%s.last-modified", jar.getName()), String.valueOf(jar.lastModified()));
        }

        File file = KeycloakConfigSourceProvider.getPersistedConfigFile().toFile();

        if (file.exists()) {
            file.delete();
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            properties.store(fos, " Auto-generated, DO NOT change this file");
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate persisted.properties file", e);
        }
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep
    void configureInfinispan(KeycloakRecorder recorder, BuildProducer<SyntheticBeanBuildItem> syntheticBeanBuildItems) {
        String pathPrefix;
        String homeDir = Environment.getHomeDir();

        if (homeDir == null) {
            pathPrefix = "";
        } else {
            pathPrefix = homeDir + "/conf/";
        }

        String configFile = getConfigValue("kc.spi.connections-infinispan.quarkus.config-file").getValue();

        if (configFile != null) {
            Path configPath = Paths.get(pathPrefix + configFile);
            String path;

            if (configPath.toFile().exists()) {
                path = configPath.toFile().getAbsolutePath();
            } else {
                path = configPath.getFileName().toString();
            }

            InputStream url = FileLookupFactory.newInstance().lookupFile(path, KeycloakProcessor.class.getClassLoader());

            if (url == null) {
                throw new IllegalArgumentException("Could not load cluster configuration file at [" + configPath + "]");
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(url))) {
                String config = reader.lines().collect(Collectors.joining("\n"));

                syntheticBeanBuildItems.produce(SyntheticBeanBuildItem.configure(CacheInitializer.class)
                        .scope(ApplicationScoped.class)
                        .unremovable()
                        .setRuntimeInit()
                        .runtimeValue(recorder.createCacheInitializer(config)).done());
            } catch (Exception cause) {
                throw new RuntimeException("Failed to read clustering configuration from [" + url + "]", cause);
            }
        } else {
            throw new IllegalArgumentException("Option 'configFile' needs to be specified");
        }
    }

    private boolean isNotPersistentProperty(String name) {
        // these properties are ignored from the build time properties as they are runtime-specific
        return !name.startsWith(NS_KEYCLOAK) || "kc.home.dir".equals(name) || CLI_ARGS.equals(name);
    }

    /**
     * This will cause quarkus tu include specified modules in the jandex index. For example keycloak-services is needed as it includes
     * most of the JAX-RS resources, which are required to register Resteasy builtin providers. See {@link ResteasyDeployment#isRegisterBuiltin()}.
     * Similar reason is liquibase
     *
     * @param indexDependencyBuildItemBuildProducer
     */
    @BuildStep
    void index(BuildProducer<IndexDependencyBuildItem> indexDependencyBuildItemBuildProducer) {
        indexDependencyBuildItemBuildProducer.produce(new IndexDependencyBuildItem("org.liquibase", "liquibase-core"));
        indexDependencyBuildItemBuildProducer.produce(new IndexDependencyBuildItem("org.keycloak", "keycloak-services"));
    }

    @BuildStep
    void initializeFilter(BuildProducer<FilterBuildItem> filters, LaunchModeBuildItem launchModeBuildItem) {
        QuarkusRequestFilter filter = new QuarkusRequestFilter();
        LaunchMode launchMode = launchModeBuildItem.getLaunchMode();

        if (launchMode.isDevOrTest()) {
            filter = new QuarkusDevRequestFilter();
        }

        filters.produce(new FilterBuildItem(filter,FilterBuildItem.AUTHORIZATION - 10));
    }

    /**
     * <p>Initialize metrics and health endpoints.
     *
     * <p>The only reason for manually registering these endpoints is that by default they run as blocking hence
     * running in a different thread than the worker thread started by {@link QuarkusRequestFilter}.
     * See https://github.com/quarkusio/quarkus/issues/12990.
     *
     * <p>By doing this, custom health checks such as {@link org.keycloak.quarkus.runtime.services.health.KeycloakReadyHealthCheck} is
     * executed within an active {@link org.keycloak.models.KeycloakSession}, making possible to use it when calculating the
     * status.
     *
     * @param routes
     */
    @BuildStep
    void initializeMetrics(BuildProducer<RouteBuildItem> routes) {
        Handler<RoutingContext> healthHandler;
        Handler<RoutingContext> metricsHandler;

        if (isMetricsEnabled()) {
            healthHandler = new SmallRyeHealthHandler();
            metricsHandler = new KeycloakMetricsHandler();
        } else {
            healthHandler = new NotFoundHandler();
            metricsHandler = new NotFoundHandler();
        }

        routes.produce(RouteBuildItem.builder().route(DEFAULT_HEALTH_ENDPOINT).handler(healthHandler).build());
        routes.produce(RouteBuildItem.builder().route(DEFAULT_HEALTH_ENDPOINT.concat("/live")).handler(healthHandler).build());
        routes.produce(RouteBuildItem.builder().route(DEFAULT_HEALTH_ENDPOINT.concat("/ready")).handler(healthHandler).build());
        routes.produce(RouteBuildItem.builder().route(KeycloakMetricsHandler.DEFAULT_METRICS_ENDPOINT).handler(metricsHandler).build());
    }

    @BuildStep
    void configureResteasy(BuildProducer<ResteasyDeploymentCustomizerBuildItem> deploymentCustomizerProducer) {
        deploymentCustomizerProducer.produce(new ResteasyDeploymentCustomizerBuildItem(new Consumer<ResteasyDeployment>() {
            @Override
            public void accept(ResteasyDeployment resteasyDeployment) {
                // we need to explicitly set the application to avoid errors at build time due to the application
                // from keycloak-services also being added to the index
                resteasyDeployment.setApplicationClass(QuarkusKeycloakApplication.class.getName());
                // we need to disable the sanitizer to avoid escaping text/html responses from the server
                resteasyDeployment.setProperty(ResteasyContextParameters.RESTEASY_DISABLE_HTML_SANITIZER, Boolean.TRUE);
            }
        }));
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    void configureDevMode(BuildProducer<HotDeploymentWatchedFileBuildItem> hotFiles) {
        hotFiles.produce(new HotDeploymentWatchedFileBuildItem("META-INF/keycloak.properties"));
    }

    private Map<Spi, Map<Class<? extends Provider>, Map<String, ProviderFactory>>> loadFactories(
            Map<String, ProviderFactory> preConfiguredProviders) {
        Config.init(new MicroProfileConfigProvider());
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        ProviderManager pm = new ProviderManager(KeycloakDeploymentInfo.create().services(), classLoader);
        Map<Spi, Map<Class<? extends Provider>, Map<String, ProviderFactory>>> factories = new HashMap<>();

        for (Spi spi : pm.loadSpis()) {
            Map<Class<? extends Provider>, Map<String, ProviderFactory>> providers = new HashMap<>();
            List<ProviderFactory> loadedFactories = new ArrayList<>(pm.load(spi));
            Map<String, ProviderFactory> deployedScriptProviders = loadDeployedScriptProviders(classLoader, spi);

            loadedFactories.addAll(deployedScriptProviders.values());
            preConfiguredProviders.putAll(deployedScriptProviders);

            for (ProviderFactory factory : loadedFactories) {
                if (IGNORED_PROVIDER_FACTORY.contains(factory.getClass())) {
                    continue;
                }

                Config.Scope scope = Config.scope(spi.getName(), factory.getId());

                if (isEnabled(factory, scope)) {
                    if (spi.isInternal() && !isInternal(factory)) {
                        ServicesLogger.LOGGER.spiMayChange(factory.getId(), factory.getClass().getName(), spi.getName());
                    }

                    providers.computeIfAbsent(spi.getProviderClass(), aClass -> new HashMap<>()).put(factory.getId(),
                            factory);
                } else {
                    logger.debugv("SPI {0} provider {1} disabled", spi.getName(), factory.getId());
                }
            }

            factories.put(spi, providers);
        }

        return factories;
    }

    private Map<String, ProviderFactory> loadDeployedScriptProviders(ClassLoader classLoader, Spi spi) {
        Map<String, ProviderFactory> providers = new HashMap<>();

        if (supportsDeployeableScripts(spi)) {
            try {
                Enumeration<URL> urls = classLoader.getResources(KEYCLOAK_SCRIPTS_JSON_PATH);

                while (urls.hasMoreElements()) {
                    URL url = urls.nextElement();
                    int fileSeparator = url.getFile().indexOf(JAR_FILE_SEPARATOR);

                    if (fileSeparator != -1) {
                        JarFile jarFile = new JarFile(url.getFile().substring("file:".length(), fileSeparator));
                        JarEntry descriptorEntry = jarFile.getJarEntry(KEYCLOAK_SCRIPTS_JSON_PATH);
                        ScriptProviderDescriptor descriptor;

                        try (InputStream is = jarFile.getInputStream(descriptorEntry)) {
                            descriptor = JsonSerialization.readValue(is, ScriptProviderDescriptor.class);
                        }

                        for (Entry<String, List<ScriptProviderMetadata>> entry : descriptor.getProviders().entrySet()) {
                            if (isScriptForSpi(spi, entry.getKey())) {
                                for (ScriptProviderMetadata metadata : entry.getValue()) {
                                    ProviderFactory provider = createDeployableScriptProvider(jarFile, entry, metadata);
                                    providers.put(metadata.getId(), provider);
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to discover script providers", e);
            }
        }

        return providers;
    }

    private ProviderFactory createDeployableScriptProvider(JarFile jarFile, Entry<String, List<ScriptProviderMetadata>> entry,
            ScriptProviderMetadata metadata) throws IOException {
        String fileName = metadata.getFileName();

        if (fileName == null) {
            throw new RuntimeException("You must provide the script file name");
        }

        JarEntry scriptFile = jarFile.getJarEntry(fileName);

        try (InputStream in = jarFile.getInputStream(scriptFile)) {
            metadata.setCode(StreamUtil.readString(in, StandardCharsets.UTF_8));
        }

        metadata.setId(new StringBuilder("script").append("-").append(fileName).toString());

        String name = metadata.getName();

        if (name == null) {
            name = fileName;
        }

        metadata.setName(name);

        return DEPLOYEABLE_SCRIPT_PROVIDERS.get(entry.getKey()).apply(metadata);
    }

    private boolean isScriptForSpi(Spi spi, String type) {
        if (spi instanceof ProtocolMapperSpi && MAPPERS.equals(type)) {
            return true;
        } else if (spi instanceof PolicySpi && POLICIES.equals(type)) {
            return true;
        } else if (spi instanceof AuthenticatorSpi && AUTHENTICATORS.equals(type)) {
            return true;
        }
        return false;
    }

    private boolean supportsDeployeableScripts(Spi spi) {
        return spi instanceof ProtocolMapperSpi || spi instanceof PolicySpi || spi instanceof AuthenticatorSpi;
    }

    private boolean isEnabled(ProviderFactory factory, Config.Scope scope) {
        if (!scope.getBoolean("enabled", true)) {
            return false;
        }
        if (factory instanceof EnvironmentDependentProviderFactory) {
            return ((EnvironmentDependentProviderFactory) factory).isSupported(scope);
        }
        return true;
    }

    private boolean isInternal(ProviderFactory<?> factory) {
        String packageName = factory.getClass().getPackage().getName();
        return packageName.startsWith("org.keycloak") && !packageName.startsWith("org.keycloak.examples");
    }

    private void checkProviders(Spi spi,
                                Map<Class<? extends Provider>, Map<String, ProviderFactory>> factoriesMap,
                                Map<Class<? extends Provider>, String> defaultProviders) {
        String defaultProvider = Config.getProvider(spi.getName());

        if (defaultProvider != null) {
            Map<String, ProviderFactory> map = factoriesMap.get(spi.getProviderClass());
            if (map == null || map.get(defaultProvider) == null) {
                throw new RuntimeException("Failed to find provider " + defaultProvider + " for " + spi.getName());
            }
        } else {
            Map<String, ProviderFactory> factories = factoriesMap.get(spi.getProviderClass());
            if (factories != null && factories.size() == 1) {
                defaultProvider = factories.values().iterator().next().getId();
            }

            if (factories != null) {
                if (defaultProvider == null) {
                    Optional<ProviderFactory> highestPriority = factories.values().stream()
                            .max(Comparator.comparing(ProviderFactory::order));
                    if (highestPriority.isPresent() && highestPriority.get().order() > 0) {
                        defaultProvider = highestPriority.get().getId();
                    }
                }
            }

            if (defaultProvider == null && (factories == null || factories.containsKey("default"))) {
                defaultProvider = "default";
            }
        }

        if (defaultProvider != null) {
            defaultProviders.put(spi.getProviderClass(), defaultProvider);
        } else {
            logger.debugv("No default provider for {0}", spi.getName());
        }
    }

    private boolean isMetricsEnabled() {
        return Configuration.getOptionalBooleanValue(MicroProfileConfigProvider.NS_KEYCLOAK_PREFIX.concat("metrics.enabled")).orElse(false);
    }
}
