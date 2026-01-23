/*
 * ShinyProxy
 *
 * Copyright (C) 2016-2026 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.shinyproxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import eu.openanalytics.containerproxy.backend.kubernetes.KubernetesSpecExtension;
import eu.openanalytics.containerproxy.model.runtime.runtimevalues.CacheHeadersMode;
import eu.openanalytics.containerproxy.model.runtime.runtimevalues.RuntimeValue;
import eu.openanalytics.containerproxy.model.spec.AccessControl;
import eu.openanalytics.containerproxy.model.spec.ContainerSpec;
import eu.openanalytics.containerproxy.model.spec.DockerDeviceRequest;
import eu.openanalytics.containerproxy.model.spec.DockerSwarmSecret;
import eu.openanalytics.containerproxy.model.spec.ISpecExtension;
import eu.openanalytics.containerproxy.model.spec.Parameters;
import eu.openanalytics.containerproxy.model.spec.PortMapping;
import eu.openanalytics.containerproxy.model.spec.ProxySpec;
import eu.openanalytics.containerproxy.service.UserService;
import eu.openanalytics.containerproxy.spec.IProxySpecProvider;
import eu.openanalytics.containerproxy.spec.ISpecExtensionProvider;
import eu.openanalytics.containerproxy.spec.expression.SpecExpressionContext;
import eu.openanalytics.containerproxy.spec.expression.SpecExpressionResolver;
import eu.openanalytics.containerproxy.spec.expression.SpelField;
import eu.openanalytics.shinyproxy.runtimevalues.CustomAppDetails;
import eu.openanalytics.shinyproxy.runtimevalues.CustomAppDetailsKey;
import eu.openanalytics.shinyproxy.runtimevalues.ShinyForceFullReloadKey;
import eu.openanalytics.shinyproxy.runtimevalues.TrackAppUrl;
import eu.openanalytics.shinyproxy.runtimevalues.WebSocketReconnectionModeKey;
import eu.openanalytics.shinyproxy.runtimevalues.WebsocketReconnectionMode;
import eu.openanalytics.shinyproxy.ShinyProxySpecProvider.ShinyProxySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

class Dummy {
    public List<ShinyProxySpec> specs;

    public List<ShinyProxySpec> getSpecs() {
        return specs;
    }

    public void setSpecs(List<ShinyProxySpec> specs) {
        this.specs = specs;
    }
}

class KubernetesSpecExtensionProviderDynamic {
    public List<KubernetesSpecExtension> specs;

    public List<KubernetesSpecExtension> getSpecs() {
        return specs;
    }

    public void setSpecs(List<KubernetesSpecExtension> specs) {
        this.specs = specs;
    }
}

/**
 * This component converts proxy specs from the 'ShinyProxy notation' into the
 * 'ContainerProxy' notation.
 * ShinyProxy notation is slightly more compact, and omits several things that
 * Shiny apps do not need,
 * such as definition of multiple containers.
 *
 * Also, if no port is specified, a port mapping is automatically created for
 * Shiny port 3838.
 */
@Component
@Primary
public class ShinyProxySpecProvider implements IProxySpecProvider {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private static final String PROP_DEFAULT_MAX_INSTANCES = "proxy.default-max-instances";
    private static final String PROP_DEFAULT_ALWAYS_SWITCH_INSTANCE = "proxy.default-always-switch-instance";
    private static Environment environment;
    private Map<String, ProxySpec> specsMap = new HashMap<>();
    private Map<String, Integer> maxInstancesCache = new HashMap<>();
    private List<ProxySpec> specs = new ArrayList<>();
    private List<TemplateGroup> templateGroups = new ArrayList<>();
    private String defaultMaxInstances;
    private String customThemeColors;
    private Boolean defaultAlwaysSwitchInstance;

    private long specsFileTs = 0;

    @Value("${proxy.authentication}")
    private String authentication;

    @Value("${proxy.container-backend:docker}")
    private String containerBackend;

    @Value("${proxy.docker.miro-image-name}")
    private String containerImage;

    @Value("${proxy.docker.admin-image-name}")
    private String containerAdminImage;

    @Value("${proxy.docker.container-network}")
    private String containerNetwork;

    @Value("${proxy.model-dir}")
    private String modelDir;

    @Value("${proxy.data-dir}")
    private String dataDir;

    @Value("${proxy.specs-path:data/specs.yaml}")
    private String specsPath;

    @Value("${proxy.miro-lang:en}")
    private String miroLang;

    @Value("${proxy.theme:default}")
    private String miroTheme;

    @Value("${proxy.force-signed-apps:false}")
    private boolean forceSignedApps;

    @Value("${proxy.max-upload-size:200}")
    private Integer maxUploadSize;

    @Value("${proxy.anonymous-readonly-mode:false}")
    private boolean anonymousReadonlyMode;

    @Value("${proxy.engine.host}")
    private String engineHost;

    @Value("${proxy.engine.ns}")
    private String engineNs;

    @Value("${proxy.engine.anonymous-user}")
    private String engineAnonymousUser;

    @Value("${proxy.engine.anonymous-admin-user:admin}")
    private String engineAnonymousAdminUser;

    @Value("${proxy.engine.anonymous-pwd:${GMS_MIRO_ENGINE_ANONYMOUS_PWD:}}")
    private String engineAnonymousPass;

    @Value("${proxy.database.host}")
    private String dbHost;

    @Value("${proxy.database.port}")
    private String dbPort;

    @Value("${proxy.database.name}")
    private String dbName;

    @Value("${proxy.database.username}")
    private String dbUname;

    @Value("${proxy.database.password:${GMS_MIRO_DATABASE_PWD:}}")
    private String dbPass;

    @Inject
    private SpecExpressionResolver expressionResolver;

    @Inject
    @Lazy
    private UserService userService;

    @Inject
    private List<ISpecExtensionProvider<?>> specExtensionProviders;

    public ShinyProxySpecProvider() {
        // cache maxInstances results for (at least) 60 minutes, since this never
        // changes during the lifetime of a session
        // maxInstancesCache = Caffeine.newBuilder()
        // .scheduler(Scheduler.systemScheduler())
        // .expireAfterAccess(60, TimeUnit.MINUTES)
        // .build();
    }

    @Autowired
    public void setEnvironment(Environment env) {
        ShinyProxySpecProvider.environment = env;
    }

    @PostConstruct
    public void afterPropertiesSet() {
        if (miroTheme.equals("custom")) {
            try {
                customThemeColors = Files
                        .readString(Path.of("/home/miroproxy/templates/2col/assets/css/themes/colors_custom.css"),
                                StandardCharsets.UTF_8)
                        .replaceAll("\\s+",
                                "");
            } catch (IOException e) {
                log.error("Error reading custom colors CSS file", e);
            }
        }
        this.setSpecs();
        this.specs.stream().collect(Collectors.groupingBy(ProxySpec::getId)).forEach((id, duplicateSpecs) -> {
            if (duplicateSpecs.size() > 1)
                throw new IllegalArgumentException(
                        String.format("Configuration error: spec with id '%s' is defined multiple times", id));
        });
        defaultMaxInstances = environment.getProperty(PROP_DEFAULT_MAX_INSTANCES, String.class, "1");
        defaultAlwaysSwitchInstance = environment.getProperty(PROP_DEFAULT_ALWAYS_SWITCH_INSTANCE, Boolean.class,
                false);
    }

    public List<ProxySpec> getSpecs() {
        try {
            File specsFile = new File(specsPath);
            if (specsFile.lastModified() > specsFileTs) {
                log.info("Reloading specs.yaml file as it was modified");
                specsMap = new HashMap<>();
                maxInstancesCache = new HashMap<>();
                LoaderOptions loaderOptions = new LoaderOptions();
                DumperOptions dumperOptions = new DumperOptions();
                Representer representer = new Representer(dumperOptions);
                representer.getPropertyUtils().setSkipMissingProperties(true);
                Yaml yaml = new Yaml(new Constructor(Dummy.class, loaderOptions), representer, dumperOptions,
                        loaderOptions);
                specsFileTs = specsFile.lastModified();
                Dummy obj = yaml.load(new FileInputStream(specsFile));
                List<ShinyProxySpec> specsTmp = obj.getSpecs();
                for (ShinyProxySpec specTmp : specsTmp) {
                    Map<String, String> containerEnv = specTmp.getContainerEnv();
                    containerEnv.put("MIRO_ENGINE_HOST", engineHost);
                    containerEnv.put("MIRO_ENGINE_NAMESPACE", engineNs);
                    containerEnv.put("MIRO_DB_HOST", dbHost);
                    containerEnv.put("MIRO_DB_PORT", dbPort);
                    containerEnv.put("MIRO_DB_NAME", dbName);
                    containerEnv.put("MIRO_MAX_UPLOAD_SIZE", Integer.toString(maxUploadSize));

                    if (!containerEnv.containsKey("MIRO_LANG")) {
                        containerEnv.put("MIRO_LANG", miroLang);
                    }
                    if (!containerEnv.containsKey("MIRO_THEME")) {
                        containerEnv.put("MIRO_THEME", miroTheme);
                        if (miroTheme.equals("custom")) {
                            containerEnv.put("MIRO_CUSTOM_THEME_COLORS", customThemeColors);
                        }
                    }

                    if (authentication.equals("none")) {
                        // ignore access groups when authentication is set to "none"
                        specTmp.setAccessGroups(null);
                        if (anonymousReadonlyMode) {
                            containerEnv.put("MIRO_MODE", "readonly");
                        }
                        containerEnv.put("SHINYPROXY_NOAUTH", "true");
                        containerEnv.put("SHINYPROXY_NOAUTH_ADMIN_NAME", engineAnonymousAdminUser);
                        containerEnv.put("MIRO_ENGINE_ANONYMOUS_USER", engineAnonymousUser);
                        containerEnv.put("MIRO_ENGINE_ANONYMOUS_PASS", engineAnonymousPass);
                    }

                    if (specTmp.getId().equals("admin")) {
                        containerEnv.put("MIRO_DB_USERNAME", dbUname);
                        containerEnv.put("MIRO_DB_PASSWORD", dbPass);
                        containerEnv.put("MIRO_ENFORCE_SIGNED_APPS", Boolean.toString(forceSignedApps));
                    }
                    specTmp.setContainerEnv(containerEnv);

                    if (containerBackend.equals("docker")) {
                        specTmp.setContainerNetwork(new SpelField.String(containerNetwork));
                        List<String> volumesTmp = specTmp.getContainerVolumes();
                        volumesTmp.set(0, modelDir.concat(volumesTmp.get(0)));
                        volumesTmp.set(1, dataDir.concat(volumesTmp.get(1)));
                        specTmp.setContainerVolumes(volumesTmp);
                    } else {
                        // kubernetes
                        specTmp.setContainerVolumes(Collections.emptyList());
                    }

                    if (specTmp.getId().equals("admin")) {
                        specTmp.setContainerImage(new SpelField.String(containerAdminImage));
                    } else {
                        specTmp.setContainerImage(new SpelField.String(containerImage));
                    }
                }

                specs = specsTmp.stream().map(ShinyProxySpec::getProxySpec).collect(Collectors.toList());
                specs.forEach(ProxySpec::setContainerIndex);
                specs.forEach(spec -> specsMap.put(spec.getId(), spec));
                specs.forEach(spec -> maxInstancesCache.put(spec.getId(), 1));
            }

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return new ArrayList<>(specs);
    }

    public void setSpecs() {
        this.specs = this.getSpecs();
    }

    public void setSpecs(List<ShinyProxySpec> specs) {
        this.specs = this.getSpecs();
    }

    public ProxySpec getSpec(String id) {
        if (id == null || id.isEmpty())
            return null;
        this.setSpecs();
        return specsMap.get(id);
    }

    public List<TemplateGroup> getTemplateGroups() {
        return templateGroups;
    }

    public void setTemplateGroups(List<TemplateGroup> templateGroups) {
        this.templateGroups = templateGroups;
    }

    public List<RuntimeValue> getRuntimeValues(ProxySpec proxy) {
        List<RuntimeValue> runtimeValues = new ArrayList<>();

        // WebsocketReconnectionMode webSocketReconnectionMode =
        // proxy.getSpecExtension(ShinyProxySpecExtension.class).getWebsocketReconnectionMode();
        WebsocketReconnectionMode webSocketReconnectionMode = WebsocketReconnectionMode.Auto;
        if (webSocketReconnectionMode == null) {
            runtimeValues.add(new RuntimeValue(WebSocketReconnectionModeKey.inst,
                    environment.getProperty("proxy.default-websocket-reconnection-mode",
                            WebsocketReconnectionMode.class, WebsocketReconnectionMode.None)));
        } else {
            runtimeValues.add(new RuntimeValue(WebSocketReconnectionModeKey.inst, webSocketReconnectionMode));
        }

        runtimeValues.add(new RuntimeValue(ShinyForceFullReloadKey.inst, getShinyForceFullReload(proxy)));

        // Boolean trackAppUrl =
        // proxy.getSpecExtension(ShinyProxySpecExtension.class).getTrackAppUrl();
        Boolean trackAppUrl = false;
        if (trackAppUrl == null) {
            trackAppUrl = environment.getProperty("proxy.default-track-app-url", Boolean.class, false);
        }
        runtimeValues.add(new RuntimeValue(TrackAppUrl.inst, trackAppUrl));
        // runtimeValues.add(new RuntimeValue(CustomAppDetailsKey.inst, new
        // CustomAppDetails(proxy.getSpecExtension(ShinyProxySpecExtension.class).getCustomAppDetails())));

        return runtimeValues;
    }

    public Integer getMaxInstancesForSpec(ProxySpec proxySpec) {
        return getMaxInstances().get(proxySpec.getId());
    }

    public Map<String, Integer> getMaxInstances() {
        return maxInstancesCache;
    }

    public Boolean getShinyForceFullReload(ProxySpec proxySpec) {
        // Boolean shinyProxyForceFullReload =
        // proxySpec.getSpecExtension(ShinyProxySpecExtension.class).getShinyForceFullReload();
        Boolean shinyProxyForceFullReload = false;
        if (shinyProxyForceFullReload != null) {
            return shinyProxyForceFullReload;
        }
        return false;
    }

    public Boolean getHideNavbarOnMainPageLink(ProxySpec proxySpec) {
        // Boolean hideNavbarOnMainPageLink =
        // proxySpec.getSpecExtension(ShinyProxySpecExtension.class).getHideNavbarOnMainPageLink();
        Boolean hideNavbarOnMainPageLink = false;
        if (hideNavbarOnMainPageLink != null) {
            return hideNavbarOnMainPageLink;
        }
        return false;
    }

    public Boolean getAlwaysShowSwitchInstance(ProxySpec proxySpec) {
        // Boolean alwaysShowSwitchInstance =
        // proxySpec.getSpecExtension(ShinyProxySpecExtension.class).getAlwaysShowSwitchInstance();
        Boolean alwaysShowSwitchInstance = null;
        if (alwaysShowSwitchInstance != null) {
            return alwaysShowSwitchInstance;
        }
        return defaultAlwaysSwitchInstance;
    }

    public static class ShinyProxySpec {

        private final ProxySpec.ProxySpecBuilder proxySpec;
        private final ContainerSpec.ContainerSpecBuilder containerSpec;
        private final AccessControl accessControl;
        private final PortMapping.PortMappingBuilder defaultPortMapping;
        private List<PortMapping> additionalPortMappings = new ArrayList<>();

        public ShinyProxySpec() {
            proxySpec = ProxySpec.builder();
            containerSpec = ContainerSpec.builder();
            accessControl = new AccessControl();
            defaultPortMapping = PortMapping.builder().name("default").port(3838);
            proxySpec.accessControl(accessControl);
        }

        public String getId() {
            return proxySpec.build().getId();
        }

        public void setId(String id) {
            proxySpec.id(id);
        }

        public String getDisplayName() {
            return proxySpec.build().getDisplayName();
        }

        public void setDisplayName(String displayName) {
            proxySpec.displayName(displayName);
        }

        public String getDescription() {
            return proxySpec.build().getDescription();
        }

        public void setDescription(String description) {
            proxySpec.description(description);
        }

        public String getLogoURL() {
            return proxySpec.build().getLogoURL();
        }

        public void setLogoURL(String logoURL) {
            proxySpec.logoURL(logoURL);
        }

        public String getLogoWidth() {
            return proxySpec.build().getLogoWidth();
        }

        public void setLogoWidth(String logoURL) {
            proxySpec.logoWidth(logoURL);
        }

        public String getLogoHeight() {
            return proxySpec.build().getLogoHeight();
        }

        public void setLogoHeight(String logoURL) {
            proxySpec.logoHeight(logoURL);
        }

        public String getLogoStyle() {
            return proxySpec.build().getLogoStyle();
        }

        public void setLogoStyle(String logoURL) {
            proxySpec.logoStyle(logoURL);
        }

        public String getLogoClasses() {
            return proxySpec.build().getLogoClasses();
        }

        public void setLogoClasses(String logoURL) {
            proxySpec.logoClasses(logoURL);
        }

        public String getFaviconPath() {
            return proxySpec.build().getFaviconPath();
        }

        public void setFaviconPath(String faviconPath) {
            proxySpec.faviconPath(faviconPath);
        }

        public SpelField.String getContainerImage() {
            return containerSpec.build().getImage();
        }

        public void setContainerImage(SpelField.String containerImage) {
            containerSpec.image(containerImage);
        }

        public SpelField.StringList getContainerCmd() {
            return containerSpec.build().getCmd();
        }

        public void setContainerCmd(List<String> containerCmd) {
            containerSpec.cmd(new SpelField.StringList(containerCmd));
        }

        public Map<String, String> getContainerEnv() {
            return containerSpec.build().getEnv().getOriginalValue();
        }

        public void setContainerEnv(Map<String, String> containerEnv) {
            containerSpec.env(new SpelField.StringMap(containerEnv));
        }

        public SpelField.String getContainerEnvFile() {
            return containerSpec.build().getEnvFile();
        }

        public void setContainerEnvFile(SpelField.String containerEnvFile) {
            containerSpec.envFile(containerEnvFile);
        }

        public SpelField.String getContainerNetwork() {
            return containerSpec.build().getNetwork();
        }

        public void setContainerNetwork(SpelField.String containerNetwork) {
            containerSpec.network(containerNetwork);
        }

        public SpelField.StringList getContainerNetworkConnections() {
            return containerSpec.build().getNetworkConnections();
        }

        public void setContainerNetworkConnections(List<String> containerNetworkConnections) {
            containerSpec.networkConnections(new SpelField.StringList(containerNetworkConnections));
        }

        public SpelField.StringList getContainerDns() {
            return containerSpec.build().getDns();
        }

        public void setContainerDns(List<String> containerDns) {
            containerSpec.dns(new SpelField.StringList(containerDns));
        }

        public List<String> getContainerVolumes() {
            return containerSpec.build().getVolumes().getOriginalValue();
        }

        public void setContainerVolumes(List<String> containerVolumes) {
            containerSpec.volumes(new SpelField.StringList(containerVolumes));
        }

        public SpelField.String getContainerMemoryRequest() {
            return containerSpec.build().getMemoryRequest();
        }

        public void setContainerMemoryRequest(SpelField.String containerMemoryRequest) {
            containerSpec.memoryRequest(containerMemoryRequest);
        }

        public SpelField.String getContainerMemoryLimit() {
            return containerSpec.build().getMemoryLimit();
        }

        public void setContainerMemoryLimit(SpelField.String containerMemoryLimit) {
            containerSpec.memoryLimit(containerMemoryLimit);
        }

        public SpelField.String getContainerCpuRequest() {
            return containerSpec.build().getCpuRequest();
        }

        public void setContainerCpuRequest(SpelField.String containerCpuRequest) {
            containerSpec.cpuRequest(containerCpuRequest);
        }

        public SpelField.String getContainerCpuLimit() {
            return containerSpec.build().getCpuLimit();
        }

        public void setContainerCpuLimit(SpelField.String containerCpuLimit) {
            containerSpec.cpuLimit(containerCpuLimit);
        }

        public boolean isContainerPrivileged() {
            return containerSpec.build().isPrivileged();
        }

        public void setContainerPrivileged(boolean containerPrivileged) {
            containerSpec.privileged(containerPrivileged);
        }

        public SpelField.StringMap getLabels() {
            return containerSpec.build().getLabels();
        }

        public void setLabels(Map<String, String> labels) {
            containerSpec.labels(new SpelField.StringMap(labels));
        }

        public int getPort() {
            return defaultPortMapping.build().getPort();
        }

        public void setPort(int port) {
            defaultPortMapping.port(port);
        }

        public String[] getAccessGroups() {
            return accessControl.getGroups();
        }

        public void setAccessGroups(String[] accessGroups) {
            accessControl.setGroups(accessGroups);
        }

        public SpelField.String getTargetPath() {
            return defaultPortMapping.build().getTargetPath();
        }

        public void setTargetPath(SpelField.String targetPath) {
            defaultPortMapping.targetPath(targetPath);
        }

        public String[] getAccessUsers() {
            return accessControl.getUsers();
        }

        public void setAccessUsers(String[] accessUsers) {
            accessControl.setUsers(accessUsers);
        }

        public String getAccessExpression() {
            return accessControl.getExpression();
        }

        public void setAccessExpression(String accessExpression) {
            accessControl.setExpression(accessExpression);
        }

        public String getAccessStrictExpression() {
            return accessControl.getStrictExpression();
        }

        public void setAccessStrictExpression(String accessStrictExpression) {
            accessControl.setStrictExpression(accessStrictExpression);
        }

        public List<DockerSwarmSecret> getDockerSwarmSecrets() {
            return containerSpec.build().getDockerSwarmSecrets();
        }

        public void setDockerSwarmSecrets(List<DockerSwarmSecret> dockerSwarmSecrets) {
            containerSpec.dockerSwarmSecrets(dockerSwarmSecrets);
        }

        public String getDockerRegistryDomain() {
            return containerSpec.build().getDockerRegistryDomain();
        }

        public void setDockerRegistryDomain(String dockerRegistryDomain) {
            containerSpec.dockerRegistryDomain(dockerRegistryDomain);
        }

        public String getDockerRegistryUsername() {
            return containerSpec.build().getDockerRegistryUsername();
        }

        public void setDockerRegistryUsername(String dockerRegistryUsername) {
            containerSpec.dockerRegistryUsername(dockerRegistryUsername);
        }

        public String getDockerRegistryPassword() {
            return containerSpec.build().getDockerRegistryPassword();
        }

        public void setDockerRegistryPassword(String dockerRegistryPassword) {
            containerSpec.dockerRegistryPassword(dockerRegistryPassword);
        }

        public Parameters getParameters() {
            return proxySpec.build().getParameters();
        }

        public void setParameters(Parameters parameters) {
            proxySpec.parameters(parameters);
        }

        public SpelField.Long getMaxLifetime() {
            return proxySpec.build().getMaxLifeTime();
        }

        public void setMaxLifetime(SpelField.Long maxLifetime) {
            proxySpec.maxLifeTime(maxLifetime);
        }

        public Boolean getStopOnLogout() {
            return proxySpec.build().getStopOnLogout();
        }

        public void setStopOnLogout(Boolean stopOnLogout) {
            proxySpec.stopOnLogout(stopOnLogout);
        }

        public SpelField.Long getHeartbeatTimeout() {
            return proxySpec.build().getHeartbeatTimeout();
        }

        public void setHeartbeatTimeout(SpelField.Long heartbeatTimeout) {
            proxySpec.heartbeatTimeout(heartbeatTimeout);
        }

        public List<PortMapping> getAdditionalPortMappings() {
            return additionalPortMappings;
        }

        public void setAdditionalPortMappings(List<PortMapping> additionalPortMappings) {
            this.additionalPortMappings = additionalPortMappings;
        }

        public boolean getAddDefaultHttpHeaders() {
            return proxySpec.build().getAddDefaultHttpHeaders();
        }

        public void setAddDefaultHttpHeaders(boolean addDefaultHeaders) {
            proxySpec.addDefaultHttpHeaders(addDefaultHeaders);
        }

        public SpelField.StringMap getHttpHeaders() {
            return proxySpec.build().getHttpHeaders();
        }

        public void setHttpHeaders(Map<String, String> headers) {
            proxySpec.httpHeaders(new SpelField.StringMap(headers));
        }

        public CacheHeadersMode getCacheHeadersMode() {
            return proxySpec.build().getCacheHeadersMode();
        }

        public void setCacheHeadersMode(CacheHeadersMode cacheHeadersMode) {
            proxySpec.cacheHeadersMode(cacheHeadersMode);
        }

        public int getMaxTotalInstances() {
            return proxySpec.build().getMaxTotalInstances();
        }

        public void setMaxTotalInstances(int maxTotalInstances) {
            proxySpec.maxTotalInstances(maxTotalInstances);
        }

        public SpelField.String getResourceName() {
            return containerSpec.build().getResourceName();
        }

        public void setResourceName(SpelField.String resourceName) {
            containerSpec.resourceName(resourceName);
        }

        public void setDockerRuntime(SpelField.String runtime) {
            containerSpec.dockerRuntime(runtime);
        }

        public SpelField.String getDockerRuntime() {
            return containerSpec.build().getDockerRuntime();
        }

        public void setDockerDeviceRequests(List<DockerDeviceRequest> dockerDeviceRequests) {
            containerSpec.dockerDeviceRequests(dockerDeviceRequests);
        }

        public List<DockerDeviceRequest> getDockerDeviceRequests() {
            return containerSpec.build().getDockerDeviceRequests();
        }

        public void setDockerUser(SpelField.String user) {
            containerSpec.dockerUser(user);
        }

        public SpelField.String getDockerUser() {
            return containerSpec.build().getDockerUser();
        }

        public void setDockerIpc(SpelField.String ipc) {
            containerSpec.dockerIpc(ipc);
        }

        public SpelField.String getDockerIpc() {
            return containerSpec.build().getDockerIpc();
        }

        public void setDockerGroupAdd(List<String> dockerGroupAdd) {
            containerSpec.dockerGroupAdd(new SpelField.StringList(dockerGroupAdd));
        }

        public SpelField.StringList getDockerGroupAdd() {
            return containerSpec.build().getDockerGroupAdd();
        }

        public ProxySpec getProxySpec() {
            additionalPortMappings.add(defaultPortMapping.build());
            containerSpec.portMapping(additionalPortMappings);
            proxySpec.containerSpecs(Collections.singletonList(containerSpec.build()));
            return proxySpec.build();
        }
    }

    public static class TemplateGroup {

        private String id;
        private Map<String, String> properties;

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

}
