package io.simakov.analytics.config;

import io.micrometer.common.KeyValue;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.simakov.analytics.gitlab.client.GitLabRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Configuration
public class WebClientConfig {

    private static final Pattern QUERY_PARAMS = Pattern.compile("\\?.*");
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("(/api/v4/[^/]+)/\\d+(.*)");
    private static final Pattern NUMERIC_SEGMENT_2 = Pattern.compile("(/api/v4/[^/]+/:id/[^/]+)/\\d+(.*)");
    private static final Pattern COMMIT_SHA = Pattern.compile("(.*)/[0-9a-f]{7,40}$");

    private static String normalizeUri(String raw) {
        String normalized = QUERY_PARAMS.matcher(raw).replaceAll("");
        normalized = NUMERIC_SEGMENT.matcher(normalized).replaceAll("$1/:id$2");
        normalized = NUMERIC_SEGMENT_2.matcher(normalized).replaceAll("$1/:id$2");
        normalized = COMMIT_SHA.matcher(normalized).replaceAll("$1/:sha");
        return normalized;
    }

    private static ExchangeFilterFunction gitLabLoggingFilter() {
        return (request, next) -> {
            long startMs = System.currentTimeMillis();
            log.debug("→ {} {}", request.method(), request.url());
            return next.exchange(request)
                .doOnNext(response -> log.debug(
                    "← {} {} ({} ms)", response.statusCode(), request.url(), System.currentTimeMillis() - startMs))
                .doOnError(ex -> log.warn(
                    "← ERROR {} {} ({} ms): {}", request.method(), request.url(), System.currentTimeMillis() - startMs, ex.getMessage()));
        };
    }

    // Normalize GitLab API URIs to low-cardinality tags for Micrometer metrics.
    // Without this, each /merge_requests/352/commits and /merge_requests/353/commits
    // would be recorded as a separate metric series, hitting Micrometer's uri-tag limit.
    @Bean
    public DefaultClientRequestObservationConvention gitLabUriNormalizationConvention() {
        return new DefaultClientRequestObservationConvention() {
            @Override
            protected KeyValue uri(ClientRequestObservationContext context) {
                KeyValue original = super.uri(context);
                return KeyValue.of(original.getKey(), normalizeUri(original.getValue()));
            }
        };
    }

    // Inject WebClient.Builder — Spring Boot auto-applies ObservationWebClientCustomizer,
    // which records http_client_requests_seconds_* metrics via Micrometer.
    private static ExchangeFilterFunction rateLimitFilter(GitLabRateLimiter rateLimiter) {
        return (request, next) -> {
            rateLimiter.acquire(request.url().toString());
            return next.exchange(request);
        };
    }

    @Bean
    public WebClient gitLabWebClient(AppProperties props,
                                     WebClient.Builder builder,
                                     GitLabRateLimiter rateLimiter) {
        AppProperties.Gitlab gitlab = props.gitlab();
        int connectMs = gitlab.connectTimeoutSeconds() * 1000;
        int readSec = gitlab.readTimeoutSeconds();

        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMs)
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(readSec, TimeUnit.SECONDS))
                .addHandlerLast(new WriteTimeoutHandler(readSec, TimeUnit.SECONDS)));

        return builder
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .filter(gitLabLoggingFilter())
            .filter(rateLimitFilter(rateLimiter))
            .build();
    }
}
