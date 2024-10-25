package utils;

import lombok.Getter;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.IndefiniteWaitOneShotStartupCheckStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static utils.TestContainersHelper.healthyOrsWaitStrategy;

/**
 * Abstract class for initializing and managing TestContainers.
 */
public abstract class ContainerInitializer {
    // @formatter:off
    private static final Map<String, String> defaultEnv = Map.of(
            "logging.level.org.heigit", "DEBUG",
            "ors.engine.graphs_data_access", "MMAP",
            "server.port", "8080",
            "ors.engine.elevation.profile_default.build.elevation", "false"
    );
    // @formatter:on

    private static List<ContainerTestImageDefaults> selectedDefaultContainers = List.of();
    private static List<ContainerTestImageBare> selectedBareContainers = List.of();

    /**
     * Initializes the containers based on the environment variable `CONTAINER_SCENARIO`.
     *
     * @param startDefaultContainers Whether to start the default containers.
     */
    public static void initializeContainers(Boolean startDefaultContainers) {
        String containerValue = System.getenv("CONTAINER_SCENARIO");
        if (containerValue == null) {
            containerValue = "all";
        }
        switch (containerValue) {
            case "war":
                selectedDefaultContainers = List.of(ContainerTestImageDefaults.WAR_CONTAINER);
                selectedBareContainers = List.of(ContainerTestImageBare.WAR_CONTAINER_BARE);
                break;
            case "jar":
                selectedDefaultContainers = List.of(ContainerTestImageDefaults.JAR_CONTAINER);
                selectedBareContainers = List.of(ContainerTestImageBare.JAR_CONTAINER_BARE);
                break;
            case "maven":
                selectedDefaultContainers = List.of(ContainerTestImageDefaults.MAVEN_CONTAINER);
                selectedBareContainers = List.of(ContainerTestImageBare.MAVEN_CONTAINER_BARE);
                break;
            default:
                // @formatter:off
                selectedDefaultContainers = List.of(
                        ContainerTestImageDefaults.WAR_CONTAINER,
                        ContainerTestImageDefaults.JAR_CONTAINER,
                        ContainerTestImageDefaults.MAVEN_CONTAINER
                );
                selectedBareContainers = List.of(
                        ContainerTestImageBare.JAR_CONTAINER_BARE,
                        ContainerTestImageBare.MAVEN_CONTAINER_BARE,
                        ContainerTestImageBare.WAR_CONTAINER_BARE
                );
                // @formatter:on
                break;
        }
    }

    /**
     * Provides a stream of default container test images for unit tests.
     *
     * @return A stream of default container test images.
     */
    public static Stream<ContainerTestImage[]> ContainerTestImageDefaultsImageStream() {
        initializeContainers(false);
        return selectedDefaultContainers.stream().map(container -> new ContainerTestImage[]{container});
    }

    /**
     * Provides a stream of bare container test images for unit tests.
     *
     * @return A stream of bare container test images.
     */
    public static Stream<ContainerTestImage[]> ContainerTestImageBareImageStream() {
        initializeContainers(false);
        return selectedBareContainers.stream().map(container -> new ContainerTestImage[]{container});
    }

    /**
     * Initializes a container with the given test image, with options to recreate and auto-start.
     * The graph mount path is set to null and will not be mounted.
     *
     * @param containerTestImage The container test image.
     * @param autoStart          Whether to auto-start the container.
     * @return The initialized container.
     */
    public static GenericContainer<?> initContainer(ContainerTestImage containerTestImage, Boolean autoStart) {
        return initContainer(containerTestImage, autoStart, null);
    }

    /**
     * Initializes a container with the given test image, with options to recreate and auto-start.
     *
     * @param containerTestImage The container test image.
     * @param autoStart          Whether to auto-start the container.
     * @param graphMountSubPath  The subpath to mount the graph. This differentiates the graph mount path for each container. If null, no graph is mounted.
     * @return The initialized container.
     */
    public static GenericContainer<?> initContainer(ContainerTestImage containerTestImage, Boolean autoStart, String graphMountSubPath) {
        if (containerTestImage == null) {
            throw new IllegalArgumentException("containerTestImage must not be null");
        }
        // @formatter:off
        Path rootPath = Path.of("../");
        GenericContainer<?> container = new GenericContainer<>(
                new ImageFromDockerfile(containerTestImage.getName(), false)
                        // Specify the copies explicitly to avoid copying the whole project
                        .withFileFromPath("Dockerfile", rootPath.resolve("ors-test-scenarios/src/test/resources/Dockerfile"))
                        .withFileFromPath("pom.xml", rootPath.resolve("pom.xml"))
                        .withFileFromPath("ors-api/pom.xml", rootPath.resolve("ors-api/pom.xml"))
                        .withFileFromPath("ors-engine/pom.xml", rootPath.resolve("ors-engine/pom.xml"))
                        .withFileFromPath("ors-report-aggregation/pom.xml", rootPath.resolve("ors-report-aggregation/pom.xml"))
                        .withFileFromPath("ors-test-scenarios/pom.xml", rootPath.resolve("ors-test-scenarios/pom.xml"))
                        .withFileFromPath("ors-engine/src/main", rootPath.resolve("ors-engine/src/main"))
                        .withFileFromPath("ors-api/src/main", rootPath.resolve("ors-api/src/main"))
                        .withFileFromPath("ors-api/src/test/files/heidelberg.test.pbf", rootPath.resolve("ors-api/src/test/files/heidelberg.test.pbf"))
                        .withFileFromPath("ors-api/src/test/files/vrn_gtfs_cut.zip", rootPath.resolve("ors-api/src/test/files/vrn_gtfs_cut.zip"))
                        .withFileFromPath("ors-config.yml", rootPath.resolve("ors-config.yml"))
                        .withFileFromPath(".dockerignore", rootPath.resolve(".dockerignore"))
                        // Special case for maven container entrypoint. This is not needed for the other containers.
                        .withFileFromPath("./ors-test-scenarios/src/test/resources/maven-entrypoint.sh", Path.of("./src/test/resources/maven-entrypoint.sh"))
                        .withTarget(containerTestImage.getName())
        )
                .withEnv(defaultEnv)
                .withExposedPorts(8080)
                .withStartupTimeout(Duration.ofSeconds(200))
                .waitingFor(healthyOrsWaitStrategy());
        // @formatter:on

        // Set the graph mount path
        if (graphMountSubPath != null) {
            Path graphMountPath = Path.of("./graphs-integrationtests/").resolve(graphMountSubPath).resolve(containerTestImage.getName());
            // Create the folder if it does not exist
            if (!graphMountPath.toFile().exists()) {
                graphMountPath.toFile().mkdirs();
            }
            container.withFileSystemBind(graphMountPath.toAbsolutePath().toString(), "/home/ors/openrouteservice/graphs", BindMode.READ_WRITE);
        }
        if (autoStart) {
            container.start();
        }

        return container;
    }

    public static void buildLayers() {
        GenericContainer<?> container = initContainer(ContainerTestImageBare.JAR_CONTAINER_BARE, false, null);
        // This is the only command I could identify that lets the container exit with code 0 reliably.
        container.setCommand("java --version > /dev/null");
        container.withStartupCheckStrategy(new IndefiniteWaitOneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)));
        container.withStartupTimeout(Duration.ofSeconds(100));
        container.setWaitStrategy(null);
        container.withLogConsumer(outputFrame -> System.out.print(outputFrame.getUtf8String()));
        container.start();
        container.stop();
    }

    /**
     * Enum representing default container test images.
     */
    @Getter
    public enum ContainerTestImageDefaults implements ContainerTestImage {
        WAR_CONTAINER("ors-test-scenarios-war"),
        JAR_CONTAINER("ors-test-scenarios-jar"),
        MAVEN_CONTAINER("ors-test-scenarios-maven");

        private final String name;

        ContainerTestImageDefaults(String name) {
            this.name = name;
        }
    }

    /**
     * Enum representing bare container test images.
     * These can be adjusted to fit specific CMD requirements.
     */
    @Getter
    public enum ContainerTestImageBare implements ContainerTestImage {
        WAR_CONTAINER_BARE("ors-test-scenarios-war-bare"), // War works different. The default CMD is hardcoded to catalina.sh run.
        JAR_CONTAINER_BARE("ors-test-scenarios-jar-bare"),
        MAVEN_CONTAINER_BARE("ors-test-scenarios-maven-bare");

        private final String name;

        ContainerTestImageBare(String name) {
            this.name = name;
        }

        public ArrayList<String> getCommand(String xmx) {
            ArrayList<String> command = new ArrayList<>();
            switch (this) {
                case JAR_CONTAINER_BARE:
                    command.add("java");
                    command.add("-Xmx" + xmx);
                    command.add("-jar");
                    command.add("ors.jar");
                    break;
                case MAVEN_CONTAINER_BARE:
                    command.add("mvn");
                    command.add("spring-boot:run");
                    command.add("-o");
                    command.add("-pl");
                    command.add("!:ors-test-scenarios,!:ors-report-aggregation,!:ors-engine");
                    command.add("-Dspring-boot.run.jvmArguments=-Xmx" + xmx);
                    command.add("-DskipTests");
                    command.add("-Dmaven.test.skip=true");
                    break;
                default:
            }
            return command;
        }
    }

    /**
     * Interface representing a container test image.
     * Allows for generic usage  of the inheriting enums.
     */
    public interface ContainerTestImage {
        String getName();
    }
}