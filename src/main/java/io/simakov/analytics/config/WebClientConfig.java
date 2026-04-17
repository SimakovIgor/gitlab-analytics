package io.simakov.analytics.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class WebClientConfig {

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

    @Bean
    public WebClient gitLabWebClient(AppProperties props) {
        AppProperties.Gitlab gitlab = props.gitlab();
        int connectMs = gitlab.connectTimeoutSeconds() * 1000;
        int readSec = gitlab.readTimeoutSeconds();

        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMs)
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(readSec, TimeUnit.SECONDS))
                .addHandlerLast(new WriteTimeoutHandler(readSec, TimeUnit.SECONDS)));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
            .filter(gitLabLoggingFilter())
            .build();
    }
}
