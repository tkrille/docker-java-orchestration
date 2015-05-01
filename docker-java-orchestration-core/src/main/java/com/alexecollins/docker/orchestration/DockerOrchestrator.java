package com.alexecollins.docker.orchestration;


import com.alexecollins.docker.orchestration.model.*;
import com.alexecollins.docker.orchestration.plugin.api.Plugin;
import com.alexecollins.docker.orchestration.util.Logs;
import com.alexecollins.docker.orchestration.util.Pinger;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.DockerException;
import com.github.dockerjava.api.InternalServerErrorException;
import com.github.dockerjava.api.NotFoundException;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.api.model.Link;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * Orchestrates multiple Docker containers based on
 */
public class DockerOrchestrator {
    /**
     * @deprecated This will be removed in a future release.
     */
    @Deprecated
    @SuppressWarnings("unused")
    public static final FileFilter DEFAULT_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return false;
        }
    };
    private static final Logger DEFAULT_LOGGER = LoggerFactory.getLogger(DockerOrchestrator.class);
    private static final String CONTAINER_IP_PATTERN = "__CONTAINER.IP__";

    private final Logger logger;
    private final DockerClient docker;
    private final Repo repo;

    private final FileOrchestrator fileOrchestrator;
    private final Set<BuildFlag> buildFlags;
    private final List<Plugin> plugins = new ArrayList<>();
    private final DockerfileValidator dockerfileValidator;
    private final DefinitionFilter definitionFilter;
    private final boolean permissionErrorTolerant;

    /**
     * @deprecated Please use builder from now on.
     */
    @Deprecated
    public DockerOrchestrator(DockerClient docker, File src, File workDir, File rootDir, String user, String project, FileFilter filter, Properties properties) {
        //noinspection deprecation
        this(docker, src, workDir, rootDir, user, project, filter, properties, EnumSet.noneOf(BuildFlag.class));
    }

    /**
     * @deprecated Please use builder from now on.
     */
    @Deprecated
    public DockerOrchestrator(DockerClient docker, File src, File workDir, File rootDir, String user, String project, FileFilter filter, Properties properties, Set<BuildFlag> buildFlags) {
        this(
                docker,
                new Repo(docker, user, project, src, properties),
                new FileOrchestrator(workDir, rootDir, filter, properties),
                buildFlags,
                DEFAULT_LOGGER,
                new DockerfileValidator(),
                DefinitionFilter.ANY,
                false);
    }

    DockerOrchestrator(DockerClient docker, Repo repo, FileOrchestrator fileOrchestrator, Set<BuildFlag> buildFlags, Logger logger, DockerfileValidator dockerfileValidator, DefinitionFilter definitionFilter, boolean permissionErrorTolerant) {
        if (docker == null) {
            throw new IllegalArgumentException("docker is null");
        }
        if (repo == null) {
            throw new IllegalArgumentException("repo is null");
        }
        if (buildFlags == null) {
            throw new IllegalArgumentException("buildFlags is null");
        }
        if (fileOrchestrator == null) {
            throw new IllegalArgumentException("fileOrchestrator is null");
        }
        if (dockerfileValidator == null) {
            throw new IllegalArgumentException("dockerfileValidator is null");
        }
        if (definitionFilter == null) {
            throw new IllegalArgumentException("definitionFilter is null");
        }

        this.docker = docker;
        this.repo = repo;
        this.fileOrchestrator = fileOrchestrator;
        this.buildFlags = buildFlags;
        this.logger = logger;
        this.dockerfileValidator = dockerfileValidator;
        this.definitionFilter = definitionFilter;
        this.permissionErrorTolerant = permissionErrorTolerant;

        for (Plugin plugin : ServiceLoader.load(Plugin.class)) {
            plugins.add(plugin);
            logger.info("Loaded " + plugin.getClass() + " plugin");
        }
    }

    public static DockerOrchestratorBuilder builder() {
        return new DockerOrchestratorBuilder();
    }

    private static boolean isPermissionError(InternalServerErrorException e) {
        return e.getMessage().contains("operation not permitted");
    }

    public void clean() {
        for (Id id : repo.ids(true)) {
            if (!inclusive(id)) {
                continue;
            }
            stop(id);
            clean(id);
        }
    }

    private boolean inclusive(Id id) {
        Conf conf = conf(id);
        if (!definitionFilter.test(id, conf)) {
            logger.info("not including " + id + ", filtered out");
            return false;
        }
        if (!conf.isEnabled()) {
            logger.info("not including " + id + ", not enabled");
            return false;
        }
        return true;
    }

    void clean(final Id id) {
        if (id == null) {
            throw new IllegalArgumentException("id is null");
        }
        stop(id);
        logger.info("Cleaning " + id);
        for (Container container : repo.findContainers(id, true)) {
            logger.info("Removing container " + container.getId());
            try {
                removeContainer(container);
            } catch (DockerException e) {
                throw new OrchestrationException(e);
            }
        }
        String imageId = null;
        try {
            imageId = repo.findImageId(id);
        } catch (NotFoundException e) {
            logger.warn("Image " + id + " not found");
        } catch (DockerException e) {
            throw new OrchestrationException(e);
        }
        if (imageId != null) {
            logger.info("Removing image " + imageId);
            try {
                docker.removeImageCmd(imageId).withForce().exec();
            } catch (DockerException e) {
                logger.warn(e.getMessage());
            }
        }
    }

    void build(final Id id) {
        if (id == null) {
            throw new IllegalArgumentException("id is null");
        }
        try {
            build(prepare(id), id);
        } catch (IOException e) {
            throw new OrchestrationException(e);
        }

    }

    private void validate(final Id id) {
        if (id == null) {
            throw new IllegalArgumentException("id is null");
        }
        try {
            dockerfileValidator.validate(repo.src(id));
        } catch (IOException e) {
            throw new OrchestrationException(e);
        }
    }

    private File prepare(Id id) throws IOException {
        if (id == null) {
            throw new IllegalArgumentException("id is null");
        }
        logger.info("Preparing " + id);
        return fileOrchestrator.prepare(id, repo.src(id), conf(id));
    }

    @SuppressWarnings(("DM_DEFAULT_ENCODING"))
    private void build(File dockerFolder, Id id) {
        try {

            String tag = repo.tag(id);
            logger.info("Building " + id + " (" + tag + ")");

            final boolean noCache = buildNoCache();
            logger.info(" - no cache: " + noCache);

            final boolean removeIntermediateImages = buildRemoveIntermediateImages();
            logger.info(" - remove intermediate images: " + removeIntermediateImages);

            final boolean quiet = buildQuiet();
            logger.info(" - quiet: " + quiet);

            BuildImageCmd build = docker.buildImageCmd(dockerFolder)
                    .withNoCache(noCache)
                    .withRemove(removeIntermediateImages)
                    .withQuiet(quiet)
                    .withTag(tag);

            throwExceptionIfThereIsAnError(build.exec());

            for (String otherTag : repo.conf(id).getTags()) {
                int lastIndexOfColon = otherTag.lastIndexOf(':');
                if (lastIndexOfColon > -1) {
                    String repositoryName = otherTag.substring(0, lastIndexOfColon);
                    String tagName = otherTag.substring(lastIndexOfColon + 1);
                    docker.tagImageCmd(repo.findImageId(id), repositoryName, tagName).withForce().exec();
                }
            }
        } catch (DockerException | IOException e) {
            throw new OrchestrationException(e);
        }

    }

    private boolean buildQuiet() {
        return haveBuildFlag(BuildFlag.QUIET);
    }

    private boolean buildRemoveIntermediateImages() {
        return haveBuildFlag(BuildFlag.REMOVE_INTERMEDIATE_IMAGES);
    }

    private boolean buildNoCache() {
        return haveBuildFlag(BuildFlag.NO_CACHE);
    }

    private boolean haveBuildFlag(BuildFlag flag) {
        return buildFlags.contains(flag);
    }

    private void start(final Id id) {
        if (id == null) {
            throw new IllegalArgumentException("id is null");
        }

        logger.info("Starting " + id);

        try {
            if (!repo.imageExists(id)) {
                logger.info("Image does not exist, so building it");
                build(id);
            }
        } catch (DockerException e) {
            throw new OrchestrationException(e);
        }

        boolean failed = false;
        try {
            Container existingContainer = repo.findContainer(id);

            if (existingContainer == null) {
                logger.info("No existing container so creating and starting new one");
                startContainer(createNewContainer(id));

            } else if (!isImageIdFromContainerMatchingProvidedImageId(existingContainer.getId(), id)) {
                logger.info("Image IDs do not match, removing container and creating new one from image");
                removeContainer(existingContainer);
                startContainer(createNewContainer(id));

            } else if (isRunning(id)) {
                logger.info("Container already running");

            } else {
                logger.info("Starting existing container " + existingContainer.getId());
                startContainer(existingContainer.getId());
            }

            for (Plugin plugin : plugins) {
                plugin.started(id, conf(id));
            }

            healthCheck(id);
            sleep(id);
        } catch (DockerException e) {
            failed = true;
            throw new OrchestrationException(e);
        } catch (OrchestrationException e) {
            failed = true;
            throw e;
        } finally {
            if (failed)
                outputContainerLog(id);
        }
    }

    private void removeContainer(Container existingContainer) {
        try {
            docker.removeContainerCmd(existingContainer.getId()).withForce().exec();
        } catch (InternalServerErrorException e) {
            if (isPermissionErrorTolerant() && isPermissionError(e)) {
                logger.warn(String.format("ignoring %s when removing container as we are configured to be permission error tolerant", e));
            } else {
                throw e;
            }
        }
    }

    private boolean isPermissionErrorTolerant() {
        return permissionErrorTolerant;
    }

    private void outputContainerLog(final Id id) {
        Container container;
        try {
            container = repo.findContainer(id);
        } catch (DockerException e) {
            throw new OrchestrationException(e);
        }

        if (container == null)
            return;

        try {
            Conf conf = conf(id);

            if (!conf.isLogOnFailure()) {
                return;
            }

            LogContainerCmd logContainerCmd = docker.logContainerCmd(container.getId()).withStdErr().withStdOut();

            if (conf.getMaxLogLines() > 0) {
                logContainerCmd.withTail(conf.getMaxLogLines());
            }

            InputStream stream = logContainerCmd.exec();

            logger.info(String.format("Logs%s from container %s: %n%s",
                    (conf.getMaxLogLines() > 0) ? " (max last " + conf.getMaxLogLines() + " lines)" : "",
                    container.getId(),
                    Logs.trimDockerLogHeaders(stream)));
        } catch (Exception e) {
            logger.warn("Unable to obtain logs from container " + container.getId() + ", will continue: ", e);
        }
    }

    private void sleep(Id id) {
        try {
            int sleep = conf(id).getSleep();
            logger.info(String.format("Sleeping for %dms", sleep));
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            throw new OrchestrationException(e);
        }
    }

    private boolean isImageIdFromContainerMatchingProvidedImageId(String containerId, final Id id) {
        try {
            String containerImageId = lookupImageIdFromContainer(containerId);
            String imageId = repo.findImageId(id);
            return containerImageId.equals(imageId);
        } catch (DockerException e) {
            logger.error("Unable to find image with id " + id, e);
            throw new OrchestrationException(e);
        }

    }

    private String lookupImageIdFromContainer(String containerId) {
        try {
            InspectContainerResponse containerInspectResponse = docker.inspectContainerCmd(containerId).exec();
            return containerInspectResponse.getImageId();
        } catch (DockerException e) {
            logger.error("Unable to inspect container " + containerId, e);
            throw new OrchestrationException(e);
        }
    }

    private void startContainer(String idOfContainerToStart) {
        try {
            docker.startContainerCmd(idOfContainerToStart).exec();
        } catch (DockerException e) {
            logger.error("Unable to start container " + idOfContainerToStart, e);
            throw new OrchestrationException(e);
        }
    }

    private Conf conf(Id id) {
        return repo.conf(id);
    }

    private String createNewContainer(Id id) throws DockerException {

        CreateContainerCmd cmd = docker.createContainerCmd(repo.findImageId(id));

        Conf conf = conf(id);

        cmd.withPublishAllPorts(true);

        Link[] links = links(id);

        logger.info(" - links " + conf.getLinks());
        cmd.withLinks(links);

        List<PortBinding> portBindings = new ArrayList<>();
        for (String e : conf.getPorts()) {

            final String[] split = e.split(" ");

            assert split.length == 1 || split.length == 2;

            final int hostPort = Integer.parseInt(split[0]);
            final int containerPort = split.length == 2 ? Integer.parseInt(split[1]) : hostPort;

            logger.info(" - port " + hostPort + "->" + containerPort);
            portBindings.add(new PortBinding(new Ports.Binding(hostPort),
                    new ExposedPort(containerPort, InternetProtocol.TCP)));
        }
        cmd.withPortBindings(portBindings.toArray(new PortBinding[portBindings.size()]));

        logger.info(" - volumes " + conf.getVolumes());

        final List<Bind> binds = new ArrayList<>();
        for (Map.Entry<String, String> entry : conf.getVolumes().entrySet()) {
            String volumePath = entry.getKey();
            String hostPath = entry.getValue();
            File file = new File(hostPath);
            String path = file.getAbsolutePath();
            logger.info(" - volumes " + volumePath + " <- " + path);
            binds.add(new Bind(path, new Volume(volumePath)));
        }

        cmd.withBinds(binds.toArray(new Bind[binds.size()]));

        cmd.withName(repo.containerName(id));
        logger.info(" - env " + conf.getEnv());
        cmd.withEnv(asEnvList(conf.getEnv()));

        return cmd.exec().getId();
    }

    /**
     * Converts String to String map to list of
     * key=value strings.
     */
    private String[] asEnvList(Map<String, String> env) {
        ArrayList<String> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            list.add(entry.getKey() + "=" + entry.getValue());
        }
        return list.toArray(new String[list.size()]);
    }

    private boolean isRunning(Id id) {
        if (id == null) {
            throw new IllegalArgumentException("id is null");
        }
        boolean running = false;
        final Container candidate = repo.findContainer(id);
        for (Container container : docker.listContainersCmd().withShowAll(false).exec()) {
            running |= candidate != null && candidate.getId().equals(container.getId());
        }
        return running;
    }

    private void healthCheck(Id id) {
        final HealthChecks healthChecks = conf(id).getHealthChecks();
        for (Ping ping : healthChecks.getPings()) {
            URI uri;
            if (ping.getUrl().toString().contains(CONTAINER_IP_PATTERN)) {
                try {
                    uri = new URI(ping.getUrl().toString().replace(CONTAINER_IP_PATTERN, getIPAddresses().get(id.toString())));
                } catch (URISyntaxException e) {
                    throw new OrchestrationException("Bad health check URI syntax: " + e.getMessage() + ", input: " + e.getInput() + ", index:" + e.getIndex());
                }
            } else {
                uri = ping.getUrl();
            }
            logger.info(String.format("Pinging %s for pattern \"%s\"", uri, ping.getPattern()));

            if (!Pinger.ping(uri, ping.getPattern(), ping.getTimeout())) {
                throw new OrchestrationException("timeout waiting for " + uri + " for " + ping.getTimeout() + " with pattern " + ping.getPattern());
            }
        }
    }

    private CreateContainerCmd prepareHostConfig(Id id) {
        CreateContainerCmd config = docker.createContainerCmd(repo.findImageId(id));

        Conf conf = conf(id);

        config.withPublishAllPorts(true);

        Link[] links = links(id);

        logger.info(" - links " + conf.getLinks());
        config.withLinks(links);

        List<PortBinding> portBindings = new ArrayList<>();
        for (String e : conf.getPorts()) {

            final String[] split = e.split(" ");

            assert split.length == 1 || split.length == 2;

            final int hostPort = Integer.parseInt(split[0]);
            final int containerPort = split.length == 2 ? Integer.parseInt(split[1]) : hostPort;

            logger.info(" - port " + hostPort + "->" + containerPort);
            portBindings.add(new PortBinding(new Ports.Binding(hostPort),
                    new ExposedPort(containerPort, InternetProtocol.TCP)));
        }
        config.withPortBindings(portBindings.toArray(new PortBinding[portBindings.size()]));

        logger.info(" - volumes " + conf.getVolumes());

        final List<Bind> binds = new ArrayList<>();
        for (Map.Entry<String, String> entry : conf.getVolumes().entrySet()) {
            String volumePath = entry.getKey();
            String hostPath = entry.getValue();
            File file = new File(hostPath);
            String path = file.getAbsolutePath();
            logger.info(" - volumes " + volumePath + " <- " + path);
            binds.add(new Bind(path, new Volume(volumePath)));
        }

        config.withBinds(binds.toArray(new Bind[binds.size()]));

        config.withName(repo.containerName(id));
        logger.info(" - env " + conf.getEnv());
        config.withEnv(asEnvList(conf.getEnv()));

        return config;
    }

    private Link[] links(Id id) {
        final List<com.alexecollins.docker.orchestration.model.Link> links = conf(id).getLinks();
        final Link[] out = new Link[links.size()];
        for (int i = 0; i < links.size(); i++) {
            com.alexecollins.docker.orchestration.model.Link link = links.get(i);
            final String name = com.alexecollins.docker.orchestration.util.Links.name(repo.findContainer(link.getId()).getNames());
            final String alias = link.getAlias();
            out[i] = new Link(name, alias);
        }
        return out;
    }

    private void stop(final Id id) {
        if (id == null) {
            throw new IllegalArgumentException("id is null");
        }

        logger.info("Stopping " + id);

        for (Container container : repo.findContainers(id, false)) {
            logger.info("Stopping container " + Arrays.toString(container.getNames()));
            try {
                docker.stopContainerCmd(container.getId()).withTimeout(1).exec();
            } catch (DockerException e) {
                throw new OrchestrationException(e);
            }
        }
        for (Plugin plugin : plugins) {
            plugin.stopped(id, conf(id));
        }
    }

    public void build() {
        for (Id id : ids()) {
            if (!inclusive(id)) {
                continue;
            }
            build(id);
        }
    }

    public void validate() {
        Exception innerException = null;
        for (Id id : ids()) {
            if (!inclusive(id)) {
                continue;
            }
            try {
                validate(id);
            } catch (Exception e) {
                innerException = e;
            }
        }
        if (innerException != null)
            throw new OrchestrationException(innerException);
    }

    public void start() {
        for (Id id : ids()) {
            if (!inclusive(id)) {
                continue;
            }
            start(id);
        }
    }

    public Map<String, String> getIPAddresses() {
        Map<String, String> idToIpAddressMap = new HashMap<>();
        for (Id id : ids()) {
            Conf conf = repo.conf(id);
            if (inclusive(id) && conf.isExposeContainerIp()) {
                String containerName = repo.containerName(id);
                InspectContainerResponse containerInspectResponse = docker.inspectContainerCmd(containerName).exec();
                idToIpAddressMap.put(id.toString(), containerInspectResponse.getNetworkSettings().getIpAddress());
            }
        }
        return idToIpAddressMap;
    }

    public void stop() {
        for (Id id : repo.ids(true)) {
            if (!inclusive(id)) {
                continue;
            }
            stop(id);
        }
    }

    public List<Id> ids() {
        return repo.ids(false);
    }

    public void push() {
        for (Id id : ids()) {
            if (!inclusive(id)) {
                continue;
            }
            push(id);
        }
    }

    private void push(Id id) {
        try {
            PushImageCmd pushImageCmd = docker.pushImageCmd(repo(id));
            logger.info("Pushing " + id + " (" + pushImageCmd.getName() + ")");
            InputStream inputStream = pushImageCmd.exec();
            throwExceptionIfThereIsAnError(inputStream);
        } catch (DockerException | IOException e) {
            throw new OrchestrationException(e);
        }
    }

    private String repo(Id id) {
        return repo.tag(id).replaceFirst(":[^:]*$", "");
    }

    private void throwExceptionIfThereIsAnError(InputStream exec) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(exec));
        String l;
        while ((l = reader.readLine()) != null) {
            logger.info(l);
            if (l.startsWith("{\"errorDetail")) {
                throw new OrchestrationException(extractMessage(l));
            }
        }
    }

    private String extractMessage(String l) {
        return l;
        //return l.replaceFirst(".*\"message\":\"([^\"]*)\".*", "$1");
    }

    public boolean isRunning() {
        for (Id id : ids()) {
            if (!isRunning(id)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    <P extends Plugin> P getPlugin(Class<P> pluginClass) {
        for (Plugin plugin : plugins) {
            if (plugin.getClass().equals(pluginClass)) {
                return (P) plugin;
            }
        }
        throw new NoSuchElementException("plugin " + pluginClass + " is not loaded");
    }
}
