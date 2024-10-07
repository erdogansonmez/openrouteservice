package org.heigit.ors.routing.graphhopper.extensions.manage;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.heigit.ors.config.EngineProperties;
import org.heigit.ors.config.profile.ProfileProperties;
import org.heigit.ors.routing.graphhopper.extensions.ORSGraphHopper;
import org.heigit.ors.routing.graphhopper.extensions.manage.local.FlatORSGraphFolderStrategy;
import org.heigit.ors.routing.graphhopper.extensions.manage.local.ORSGraphFileManager;
import org.heigit.ors.routing.graphhopper.extensions.manage.local.ORSGraphFolderStrategy;
import org.heigit.ors.routing.graphhopper.extensions.manage.remote.*;

import java.io.File;
import java.nio.file.Path;

import static java.util.Optional.ofNullable;

public class ORSGraphManager {

    private static final Logger LOGGER = Logger.getLogger(ORSGraphManager.class.getName());
    private static final String UPDATE_LOCKFILE_NAME = "update.lock";
    private static final String RESTART_LOCKFILE_NAME = "restart.lock";

    private GraphManagementRuntimeProperties managementRuntimeProperties;
    private ORSGraphFileManager orsGraphFileManager;
    private ORSGraphRepoManager orsGraphRepoManager;

    public ORSGraphManager() {
    }

    public ORSGraphManager(GraphManagementRuntimeProperties managementRuntimeProperties, ORSGraphFileManager orsGraphFileManager, ORSGraphRepoManager orsGraphRepoManager) {
        this.managementRuntimeProperties = managementRuntimeProperties;
        this.orsGraphFileManager = orsGraphFileManager;
        this.orsGraphRepoManager = orsGraphRepoManager;
    }


    public static ORSGraphManager initializeGraphManagement(String graphVersion, EngineProperties engineProperties, ProfileProperties profileProperties) {
        GraphManagementRuntimeProperties managementProps = GraphManagementRuntimeProperties.Builder.from(engineProperties, profileProperties, graphVersion).build();
        ORSGraphManager orsGraphManager = initializeGraphManagement(managementProps);
        profileProperties.setGraphPath(Path.of(orsGraphManager.getActiveGraphDirAbsPath()));
        return orsGraphManager;
    }


    public static ORSGraphManager initializeGraphManagement(GraphManagementRuntimeProperties managementProps) {
        ORSGraphFolderStrategy orsGraphFolderStrategy = new FlatORSGraphFolderStrategy(managementProps);
        ORSGraphRepoStrategy orsGraphRepoStrategy = new NamedGraphsRepoStrategy(managementProps);
        ORSGraphFileManager orsGraphFileManager = new ORSGraphFileManager(managementProps, orsGraphFolderStrategy);
        orsGraphFileManager.initialize();

        ORSGraphRepoManager orsGraphRepoManager = getOrsGraphRepoManager(managementProps, orsGraphRepoStrategy, orsGraphFileManager);

        ORSGraphManager orsGraphManager = new ORSGraphManager(managementProps, orsGraphFileManager, orsGraphRepoManager);
        orsGraphManager.manageStartup();
        return orsGraphManager;
    }

    public static ORSGraphRepoManager getOrsGraphRepoManager(GraphManagementRuntimeProperties managementProps, ORSGraphRepoStrategy orsGraphRepoStrategy, ORSGraphFileManager orsGraphFileManager) {
        ORSGraphRepoManager orsGraphRepoManager = new NullRepoManager();

        switch (managementProps.getDerivedRepoType()) {
            case HTTP -> {
                LOGGER.debug("Using HttpRepoManager for repoUrl %s".formatted(managementProps.getDerivedRepoBaseUrl()));
                orsGraphRepoManager = new HttpRepoManager(managementProps, orsGraphRepoStrategy, orsGraphFileManager);
            }
            case FILESYSTEM -> {
                LOGGER.debug("Using FileSystemRepoManager for repoUri %s".formatted(managementProps.getDerivedRepoPath()));
                orsGraphRepoManager = new FileSystemRepoManager(managementProps, orsGraphRepoStrategy, orsGraphFileManager);
            }
            case NULL -> {
                LOGGER.debug("No valid repositoryUri configured, using NullRepoManager.");
                orsGraphRepoManager = new NullRepoManager();
            }
        }

        return orsGraphRepoManager;
    }

    public ProfileProperties loadProfilePropertiesFromActiveGraph(ORSGraphManager orsGraphManager, ProfileProperties profileProperties) {
        profileProperties.mergeLoaded(orsGraphManager.getActiveGraphProfileProperties());
        return profileProperties;
    }

    public String getQualifiedProfileName() {
        return orsGraphFileManager.getProfileDescriptiveName();
    }

    public String getActiveGraphDirAbsPath() {
        return orsGraphFileManager.getActiveGraphDirAbsPath();
    }

    public boolean isBusy() {
        return orsGraphFileManager.isBusy();
    }

    public boolean hasGraphDownloadFile() {
        return orsGraphFileManager.hasGraphDownloadFile();
    }

    public boolean hasDownloadedExtractedGraph() {
        return orsGraphFileManager.hasDownloadedExtractedGraph();
    }

    public boolean useGraphRepository() {
        if (managementRuntimeProperties == null) return false;
        if (!managementRuntimeProperties.isEnabled()) return false;
        if (StringUtils.isBlank(managementRuntimeProperties.getRepoName())) return false;

        return managementRuntimeProperties.getDerivedRepoType() != GraphManagementRuntimeProperties.GraphRepoType.NULL;
    }

    public void manageStartup() {
        if (!useGraphRepository()) return;

        orsGraphFileManager.cleanupIncompleteFiles();

        boolean hasActiveGraph = orsGraphFileManager.hasActiveGraph();
        boolean hasDownloadedExtractedGraph = orsGraphFileManager.hasDownloadedExtractedGraph();

        if (!hasActiveGraph && !hasDownloadedExtractedGraph && useGraphRepository()) {
            LOGGER.debug("[%s] No local graph or extracted downloaded graph found - trying to download and extract graph from repository".formatted(getQualifiedProfileName()));
            downloadAndExtractLatestGraphIfNecessary();
            orsGraphFileManager.activateExtractedDownloadedGraph();
        }
        if (!hasActiveGraph && hasDownloadedExtractedGraph) {
            LOGGER.debug("[%s] Found extracted downloaded graph only".formatted(getQualifiedProfileName()));
            orsGraphFileManager.activateExtractedDownloadedGraph();
        }
        if (hasActiveGraph && hasDownloadedExtractedGraph) {
            LOGGER.debug("[%s] Found local graph and extracted downloaded graph".formatted(getQualifiedProfileName()));
            orsGraphFileManager.backupExistingGraph();
            orsGraphFileManager.activateExtractedDownloadedGraph();
        }
        if (hasActiveGraph && !hasDownloadedExtractedGraph) {
            LOGGER.debug("[%s] Found local graph only".formatted(getQualifiedProfileName()));
        }
    }

    public void downloadAndExtractLatestGraphIfNecessary() {
        if (!useGraphRepository()) return;
        if (orsGraphFileManager.isBusy()) {
            LOGGER.debug("[%s] ORSGraphManager is busy - skipping download".formatted(getQualifiedProfileName()));
            return;
        }
        orsGraphRepoManager.downloadGraphIfNecessary();
        orsGraphFileManager.extractDownloadedGraph();
    }

    public boolean hasUpdateLock() {
        File restartLockFile = new File(orsGraphFileManager.getGraphsRootDirAbsPath() + File.separator + UPDATE_LOCKFILE_NAME);
        return restartLockFile.exists();
    }

    public boolean hasRestartLock() {
        File restartLockFile = new File(orsGraphFileManager.getGraphsRootDirAbsPath() + File.separator + RESTART_LOCKFILE_NAME);
        return restartLockFile.exists();
    }

    public void writeOrsGraphInfoFileIfNotExists(ORSGraphHopper gh) {
        orsGraphFileManager.writeOrsGraphInfoFileIfNotExists(gh);
    }

    public GraphInfo getActiveGraphInfo() {
        return orsGraphFileManager.getActiveGraphInfo();
    }

    public ProfileProperties getActiveGraphProfileProperties() {
        return ofNullable(getActiveGraphInfo())
                .map(GraphInfo::getPersistedGraphInfo)
                .map(PersistedGraphInfo::getProfileProperties)
                .orElse(null);
    }
}
