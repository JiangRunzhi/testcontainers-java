package org.testcontainers.utility;

import com.github.dockerjava.api.model.AuthConfig;
import org.intellij.lang.annotations.Language;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;
import org.testcontainers.DockerRegistryContainer;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.rnorth.visibleassertions.VisibleAssertions.assertTrue;
import static org.testcontainers.TestImages.DOCKER_REGISTRY_IMAGE;

/**
 * This test checks the integration between Testcontainers and an authenticated registry, but uses
 * a mock instance of {@link RegistryAuthLocator} - the purpose of the test is solely to ensure that
 * the auth locator is utilised, and that the credentials it provides flow through to the registry.
 * <p>
 * {@link RegistryAuthLocatorTest} covers actual credential scenarios at a lower level, which are
 * impractical to test end-to-end.
 */
public class AuthenticatedImagePullTest {

    /**
     * Containerised docker image registry, with simple hardcoded credentials
     */
    @ClassRule
    public static DockerRegistryContainer authenticatedRegistry = new DockerRegistryContainer(new ImageFromDockerfile()
        .withDockerfileFromBuilder(builder -> {
            builder.from(DOCKER_REGISTRY_IMAGE.asCanonicalNameString())
                .run("htpasswd -Bbn testuser notasecret > /htpasswd")
                .env("REGISTRY_AUTH", "htpasswd")
                .env("REGISTRY_AUTH_HTPASSWD_PATH", "/htpasswd")
                .env("REGISTRY_AUTH_HTPASSWD_REALM", "Test");
        }));

    private static RegistryAuthLocator originalAuthLocatorSingleton;

    private final DockerImageName testImageName = authenticatedRegistry.createImage();

    @BeforeClass
    public static void beforeClass() throws Exception {
        originalAuthLocatorSingleton = RegistryAuthLocator.instance();

        String testRegistryAddress = authenticatedRegistry.getEndpoint();

        final AuthConfig authConfig = new AuthConfig()
            .withUsername("testuser")
            .withPassword("notasecret")
            .withRegistryAddress("http://" + testRegistryAddress);

        // Replace the RegistryAuthLocator singleton with our mock, for the duration of this test
        final RegistryAuthLocator mockAuthLocator = Mockito.mock(RegistryAuthLocator.class);
        RegistryAuthLocator.setInstance(mockAuthLocator);
        when(mockAuthLocator.lookupAuthConfig(argThat(argument -> testRegistryAddress.equals(argument.getRegistry())), any()))
            .thenReturn(authConfig);
    }

    @AfterClass
    public static void tearDown() {
        RegistryAuthLocator.setInstance(originalAuthLocatorSingleton);
    }

    @Test
    public void testThatAuthLocatorIsUsedForContainerCreation() {
        // actually start a container, which will require an authenticated pull
        try (final GenericContainer<?> container = new GenericContainer<>(testImageName)
            .withCommand("/bin/sh", "-c", "sleep 10")) {
            container.start();

            assertTrue("container started following an authenticated pull", container.isRunning());
        }
    }

    @Test
    public void testThatAuthLocatorIsUsedForDockerfileBuild() throws IOException {
        // Prepare a simple temporary Dockerfile which requires our custom private image
        Path tempFile = getLocalTempFile(".Dockerfile");
        String dockerFileContent = "FROM " + testImageName.asCanonicalNameString();
        Files.write(tempFile, dockerFileContent.getBytes());

        // Start a container built from a derived image, which will require an authenticated pull
        try (final GenericContainer<?> container = new GenericContainer<>(
            new ImageFromDockerfile()
                .withDockerfile(tempFile)
        )
            .withCommand("/bin/sh", "-c", "sleep 10")) {
            container.start();

            assertTrue("container started following an authenticated pull", container.isRunning());
        }
    }

    @Test
    public void testThatAuthLocatorIsUsedForDockerComposePull() throws IOException {
        // Prepare a simple temporary Docker Compose manifest which requires our custom private image
        Path tempFile = getLocalTempFile(".docker-compose.yml");
        @Language("yaml") String composeFileContent =
            "version: '2.0'\n" +
                "services:\n" +
                "  privateservice:\n" +
                "      command: /bin/sh -c 'sleep 60'\n" +
                "      image: " + testImageName.asCanonicalNameString();
        Files.write(tempFile, composeFileContent.getBytes());

        // Start the docker compose project, which will require an authenticated pull
        try (final DockerComposeContainer<?> compose = new DockerComposeContainer<>(tempFile.toFile())) {
            compose.start();

            assertTrue("container started following an authenticated pull",
                compose
                    .getContainerByServiceName("privateservice_1")
                    .map(ContainerState::isRunning)
                    .orElse(false)
            );
        }
    }

    private Path getLocalTempFile(String s) throws IOException {
        Path projectRoot = Paths.get(".");
        Path tempDirectory = Files.createTempDirectory(projectRoot, this.getClass().getSimpleName() + "-test-");
        Path relativeTempDirectory = projectRoot.relativize(tempDirectory);
        Path tempFile = Files.createTempFile(relativeTempDirectory, "test", s);

        tempDirectory.toFile().deleteOnExit();
        tempFile.toFile().deleteOnExit();

        return tempFile;
    }
}
