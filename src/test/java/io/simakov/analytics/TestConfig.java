package io.simakov.analytics;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestConfig {

    /** Port must match spring.mail.port=3025 in application-test.yml. */
    private static final int TEST_SMTP_PORT = 3025;

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource")
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("gitlab_analytics_test")
            .withUsername("analytics")
            .withPassword("analytics");
    }

    /**
     * In-memory SMTP server for integration tests.
     * Captures outgoing email so tests can assert on sent messages.
     * Bound to the same port as spring.mail.port in application-test.yml.
     */
    @Bean(destroyMethod = "stop")
    GreenMail greenMail() {
        GreenMail server = new GreenMail(
            new ServerSetup(TEST_SMTP_PORT, "localhost", ServerSetup.PROTOCOL_SMTP))
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());
        server.start();
        return server;
    }
}
