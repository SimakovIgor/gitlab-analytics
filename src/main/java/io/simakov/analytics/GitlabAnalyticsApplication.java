package io.simakov.analytics;

import io.simakov.analytics.config.AppProperties;
import io.simakov.analytics.insights.InsightProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({AppProperties.class, InsightProperties.class})
public class GitlabAnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(GitlabAnalyticsApplication.class, args);
    }
}
