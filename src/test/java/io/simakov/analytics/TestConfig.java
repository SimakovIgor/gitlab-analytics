package io.simakov.analytics;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestConfig {

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource")
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("gitlab_analytics_test")
            .withUsername("analytics")
            .withPassword("analytics");
    }
}
