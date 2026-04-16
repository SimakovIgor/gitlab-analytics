package io.simakov.analytics.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

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
            .build();
    }
}
