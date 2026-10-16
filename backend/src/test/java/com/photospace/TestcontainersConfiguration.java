package com.photospace;

import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Starts throwaway PostgreSQL, S3, and SQS emulators that match compose.yaml. The {@code compose-db} profile
 * turns them off so {@link TestBackendApplication} can use the compose.yaml services instead (the defaults in
 * application.yml already point at them).
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("!compose-db")
public class TestcontainersConfiguration {

    public static final String BUCKET = "photo-space-test";
    private static final String QUEUE_PATH = "/000000000000/photo-space-jobs";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
    }

    @Bean
    GenericContainer<?> s3Container() {
        return new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.2.3"))
                .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
                .withExposedPorts(9090)
                .waitingFor(Wait.forHttp("/").forPort(9090));
    }

    @Bean
    GenericContainer<?> sqsContainer() {
        // The same queues as local development, including the dead-letter queue (run from backend/).
        return new GenericContainer<>(DockerImageName.parse("softwaremill/elasticmq-native:1.7.1"))
                .withCopyFileToContainer(
                        MountableFile.forHostPath(Path.of("..", "local", "elasticmq.conf")), "/opt/elasticmq.conf")
                .withExposedPorts(9324)
                .waitingFor(Wait.forHttp("/?Action=ListQueues").forPort(9324));
    }

    @Bean
    DynamicPropertyRegistrar awsEmulatorProperties(GenericContainer<?> s3Container, GenericContainer<?> sqsContainer) {
        return registry -> {
            registry.add("app.storage.bucket", () -> BUCKET);
            registry.add("app.storage.endpoint", () -> endpoint(s3Container, 9090));
            registry.add("app.jobs.sqs-endpoint", () -> endpoint(sqsContainer, 9324));
            registry.add("app.jobs.queue-url", () -> endpoint(sqsContainer, 9324) + QUEUE_PATH);
        };
    }

    private static String endpoint(GenericContainer<?> container, int port) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(port);
    }
}
