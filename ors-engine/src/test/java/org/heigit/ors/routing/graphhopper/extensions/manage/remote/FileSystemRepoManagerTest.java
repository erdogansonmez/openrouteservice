package org.heigit.ors.routing.graphhopper.extensions.manage.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.io.FileUtils;
import org.heigit.ors.common.EncoderNameEnum;
import org.heigit.ors.config.profile.ProfileProperties;
import org.heigit.ors.routing.graphhopper.extensions.manage.GraphManagementRuntimeProperties;
import org.heigit.ors.routing.graphhopper.extensions.manage.ORSGraphInfoV1;
import org.heigit.ors.routing.graphhopper.extensions.manage.local.FlatORSGraphFolderStrategy;
import org.heigit.ors.routing.graphhopper.extensions.manage.local.ORSGraphFileManager;
import org.heigit.ors.routing.graphhopper.extensions.manage.local.ORSGraphFolderStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemRepoManagerTest {

    private static final long EARLIER_DATE = 1692373111000L; // Fr 18. Aug 17:38:31 CEST 2023
    private static final long MIDDLE_DATE = 1692373222000L;  // Fr 18. Aug 17:40:22 CEST 2023
    private static final long LATER_DATE = 1692373333000L;   // Fr 18. Aug 17:42:13 CEST 2023

    private static final long REPO_HGV_OSM_DATE       = 1706264611000L; // "2024-01-26T10:23:31+0000"
    private static final long REPO_HGV_IMPORT_DATE    = 1719397419000L; // "2024-06-26T10:23:39+0000"

    private static final String REPO_GRAPHS_REPO_NAME = "vendor-xyz";
    private static final String REPO_GRAPHS_PROFILE_GROUP = "fastisochrones";
    private static final String REPO_GRAPHS_COVERAGE = "heidelberg";
    private static final String REPO_GRAPHS_VERSION = "1";
    private static final String ENCODER_NAME = "driving-hgv";

    @TempDir(cleanup = CleanupMode.ALWAYS)
    private Path TEMP_DIR;

    private String testReposPath = "src/test/resources/test-filesystem-repos";
    private Path localGraphsRootPath;

    FileSystemRepoManager fileSystemRepoManager;

    @BeforeEach
    public void setUp() throws IOException {
        localGraphsRootPath = TEMP_DIR.resolve("graphs");
        Files.createDirectories(localGraphsRootPath);
    }

    @AfterEach
    void deleteFiles() throws IOException {
        FileUtils.deleteDirectory(localGraphsRootPath.toFile());
    }

    void writeORSGraphInfoToGraphPath(String profile, Date importDate, Date osmDate) throws IOException {
        ORSGraphInfoV1 localOrsGraphInfoV1Object = createOrsGraphInfoV1(profile, importDate, osmDate);
        String ymlFileName = profile + "/graph_info.yml";
        Path graphInfoFilePath = localGraphsRootPath.resolve(ymlFileName);
        Files.createDirectories(graphInfoFilePath.getParent());
        File graphInfoV1File = Files.createFile(graphInfoFilePath).toFile();
        new ObjectMapper(YAMLFactory.builder().build()).writeValue(graphInfoV1File, localOrsGraphInfoV1Object);
    }

    private FileSystemRepoManager createFileSystemRepoManager() {
        return createFileSystemRepoManager(ENCODER_NAME);
    }
    private FileSystemRepoManager createFileSystemRepoManager(String profileName) {
        GraphManagementRuntimeProperties managementProps = GraphManagementRuntimeProperties.Builder.empty()
                .withLocalGraphsRootAbsPath(localGraphsRootPath.toString())
                .withLocalProfileName(profileName)
                .withRepoBaseUri(testReposPath)
                .withRepoName(REPO_GRAPHS_REPO_NAME)
                .withRepoProfileGroup(REPO_GRAPHS_PROFILE_GROUP)
                .withRepoCoverage(REPO_GRAPHS_COVERAGE)
                .withEncoderName(ENCODER_NAME)
                .withGraphVersion(REPO_GRAPHS_VERSION)
                .build();

        ORSGraphFolderStrategy orsGraphFolderStrategy = new FlatORSGraphFolderStrategy(managementProps);
        ORSGraphFileManager orsGraphFileManager = new ORSGraphFileManager(managementProps, orsGraphFolderStrategy);
        orsGraphFileManager.initialize();

        ORSGraphRepoStrategy orsGraphRepoStrategy = new NamedGraphsRepoStrategy(managementProps);
        return new FileSystemRepoManager(managementProps, orsGraphRepoStrategy, orsGraphFileManager);
    }

    private static ORSGraphInfoV1 createOrsGraphInfoV1(String profile, Date importDate, Date osmDate) {
        ORSGraphInfoV1 orsGraphInfoV1 = new ORSGraphInfoV1();
        orsGraphInfoV1.setImportDate(importDate);
        orsGraphInfoV1.setOsmDate(osmDate);
        ProfileProperties profileProperties = ProfileProperties.getProfileInstance(EncoderNameEnum.WHEELCHAIR);
        orsGraphInfoV1.setProfileProperties(profileProperties);
        return orsGraphInfoV1;
    }

    @Test
    void downloadLatestGraphInfoFromRepository() {
        fileSystemRepoManager = createFileSystemRepoManager();
        fileSystemRepoManager.downloadLatestGraphInfoFromRepository();
        assertTrue(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.yml").toFile().exists());
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.ghz").toFile().exists());
    }

    @Test
    void downloadGraphIfNecessary_noLocalData_remoteDataExists() {
        fileSystemRepoManager = createFileSystemRepoManager();
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.yml").toFile().exists());
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.ghz").toFile().exists());
        fileSystemRepoManager.downloadGraphIfNecessary();
        assertTrue(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.yml").toFile().exists());
        assertTrue(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.ghz").toFile().exists());
    }

    @Test
    void downloadGraphIfNecessary_localDataExists_noRemoteData() throws IOException {
        fileSystemRepoManager = createFileSystemRepoManager("scooter");
        writeORSGraphInfoToGraphPath("wheelchair", new Date(LATER_DATE), new Date(EARLIER_DATE));
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_wheelchair.yml").toFile().exists());
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_wheelchair.ghz").toFile().exists());
        fileSystemRepoManager.downloadGraphIfNecessary();
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_wheelchair.yml").toFile().exists());
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_wheelchair.ghz").toFile().exists());
    }

    @Test
    void downloadGraphIfNecessary_localDate_before_remoteDate() throws IOException {
        fileSystemRepoManager = createFileSystemRepoManager();
        writeORSGraphInfoToGraphPath(ENCODER_NAME, new Date(REPO_HGV_OSM_DATE - 1000000), new Date(REPO_HGV_IMPORT_DATE - 1000000));
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.yml").toFile().exists());
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.ghz").toFile().exists());
        fileSystemRepoManager.downloadGraphIfNecessary();
        assertTrue(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.yml").toFile().exists());
        assertTrue(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.ghz").toFile().exists());
    }

    @Test
    void downloadGraphIfNecessary_localDate_equals_remoteDate() throws IOException {
        fileSystemRepoManager = createFileSystemRepoManager();
        writeORSGraphInfoToGraphPath(ENCODER_NAME, new Date(REPO_HGV_IMPORT_DATE), new Date(REPO_HGV_OSM_DATE));
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.yml").toFile().exists());
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.ghz").toFile().exists());
        fileSystemRepoManager.downloadGraphIfNecessary();
        assertTrue(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.yml").toFile().exists());
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.ghz").toFile().exists());
    }

    @Test
    void downloadGraphIfNecessary_localDate_after_remoteDate() throws IOException {
        fileSystemRepoManager = createFileSystemRepoManager();
        writeORSGraphInfoToGraphPath(ENCODER_NAME, new Date(REPO_HGV_IMPORT_DATE + 1000000), new Date(REPO_HGV_OSM_DATE + 1000000));
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.yml").toFile().exists());
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.ghz").toFile().exists());
        fileSystemRepoManager.downloadGraphIfNecessary();
        assertTrue(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.yml").toFile().exists());
        assertFalse(localGraphsRootPath.resolve("vendor-xyz_fastisochrones_heidelberg_1_driving-hgv.ghz").toFile().exists());
    }
}