package utils;

import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startables;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public abstract class ContainerInitializer {
    private static final Map<String, String> defaultEnv = Map.of("logging.level.org.heigit", "INFO", "ors.engine.graphs_data_access", "MMAP");
    private static GenericContainer<?> warContainer;
    private static GenericContainer<?> jarContainer;

    static {
        Startables.deepStart(initContainer(ContainerTestImage.JAR_CONTAINER, false), initContainer(ContainerTestImage.WAR_CONTAINER, false)).join();
    }

    public static Stream<Object[]> imageStream() {
        return Stream.of(new Object[]{ContainerTestImage.JAR_CONTAINER}, new Object[]{ContainerTestImage.WAR_CONTAINER});
    }

    public static GenericContainer<?> initContainer(ContainerTestImage containerTestImage) {
        return initContainer(containerTestImage, true);
    }

    public static GenericContainer<?> initContainer(ContainerTestImage containerTestImage, Boolean autoStart) {
        if (containerTestImage == null) {
            throw new IllegalArgumentException("containerTestImage must not be null");
        }
        //@formatter:off
        WaitStrategy waitStrategy = new HttpWaitStrategy()
                .forPort(8080)
                .forStatusCode(200)
                .forPath("/ors/v2/health")
                .withStartupTimeout(Duration.ofSeconds(80));
        //@formatter:off
        GenericContainer<?> container = new GenericContainer<>(
                new ImageFromDockerfile(containerTestImage.getName(), false)
                        .withFileFromPath("ors-api", Path.of("../ors-api"))
                        .withFileFromPath("ors-engine", Path.of("../ors-engine"))
                        .withFileFromPath("ors-report-aggregation", Path.of("../ors-report-aggregation"))
                        .withFileFromPath("pom.xml", Path.of("../pom.xml"))
                        .withFileFromPath("ors-config.yml", Path.of("../ors-config.yml"))
                        .withFileFromPath("Dockerfile", Path.of("../ors-test-scenarios/src/test/resources/Dockerfile"))
                        .withFileFromPath(".dockerignore", Path.of("../.dockerignore"))
                        .withTarget(containerTestImage.getName())
        )
                .withEnv(defaultEnv)
                .withFileSystemBind("./graphs-integrationtests/" + containerTestImage.getName(),
                        "/home/ors/openrouteservice/graphs", BindMode.READ_WRITE)
                .withExposedPorts(8080)
                .withLogConsumer(outputFrame -> System.out.print(outputFrame.getUtf8String()))
                .waitingFor(waitStrategy);

        if (containerTestImage == ContainerTestImage.WAR_CONTAINER) {
            if (warContainer == null) {
                warContainer = container;
            }
            if (autoStart && !warContainer.isRunning()) {
                warContainer.start();
            }
            return warContainer;
        } else {
            if (jarContainer == null) {
                jarContainer = container;
            }
            if (autoStart && !jarContainer.isRunning())
                jarContainer.start();
            return jarContainer;
        }
    }

    protected void restartContainer(GenericContainer<?> container) throws IOException, InterruptedException {
        container.stop();
        container.start();
    }


    @BeforeEach
    public void resetEnv() {
        if (warContainer != null) {
            warContainer.setEnv(List.of());
            warContainer.withEnv(defaultEnv);
        }
        if (jarContainer != null) {
            jarContainer.setEnv(List.of());
            jarContainer.withEnv(defaultEnv);
        }
    }

    // Create enum for available test images
    public enum ContainerTestImage {
        WAR_CONTAINER("ors-test-scenarios-war"), JAR_CONTAINER("ors-test-scenarios-jar");

        private final String name;

        ContainerTestImage(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}