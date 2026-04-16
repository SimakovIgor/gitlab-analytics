package io.simakov.analytics;

import io.simakov.analytics.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(AppProperties.class)
public class GitlabAnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(GitlabAnalyticsApplication.class, args);
    }
}
