package io.simakov.analytics.jira;

import io.simakov.analytics.jira.dto.JiraIssueDto;
import io.simakov.analytics.jira.dto.JiraSearchResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@Component
@ConditionalOnExpression("!T(org.springframework.util.StringUtils).isEmpty('${app.jira.base-url:}')")
public class JiraApiClient {

    private static final DateTimeFormatter JIRA_DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final int PAGE_SIZE = 100;

    private final WebClient webClient;
    private final JiraProperties jiraProperties;

    public JiraApiClient(JiraProperties jiraProperties) {
        this.jiraProperties = jiraProperties;
        String credentials = jiraProperties.username() + ":" + jiraProperties.apiToken();
        String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8));

        this.webClient = WebClient.builder()
            .baseUrl(jiraProperties.baseUrl())
            .defaultHeader("Authorization", "Basic " + basicAuth)
            .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
            .build();
    }

    /**
     * Fetches all Jira incidents from the configured project created on or after {@code since}.
     * Paginates automatically until all matching issues are retrieved.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    public List<JiraIssueDto> fetchIncidents(Instant since) {
        String jql = "project = \"" + jiraProperties.projectKey()
            + "\" AND issuetype = Incident AND created >= \""
            + JIRA_DATE_FMT.format(since) + "\"";
        String fields = "summary,created,resolutiondate,components";

        log.info("Jira search: {}", jql);

        List<JiraIssueDto> all = new ArrayList<>();
        int startAt = 0;
        int maxResults = Math.min(PAGE_SIZE, jiraProperties.maxResults());
        Duration timeout = Duration.ofSeconds(jiraProperties.readTimeoutSeconds());

        while (true) {
            try {
                final int offset = startAt;
                JiraSearchResponseDto page = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                        .path("/rest/api/2/search")
                        .queryParam("jql", jql)
                        .queryParam("fields", fields)
                        .queryParam("startAt", offset)
                        .queryParam("maxResults", maxResults)
                        .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                            .map(body -> new JiraApiException(
                                "Jira API error " + response.statusCode() + ": " + body)))
                    .bodyToMono(JiraSearchResponseDto.class)
                    .block(timeout);

                if (page == null || page.issues() == null || page.issues().isEmpty()) {
                    break;
                }

                all.addAll(page.issues());
                log.debug("Jira page startAt={}, fetched={}, total={}", startAt, page.issues().size(), page.total());

                if (all.size() >= page.total()) {
                    break;
                }
                startAt += page.issues().size();
            } catch (JiraApiException e) {
                log.error("Jira API error during incident fetch: {}", e.getMessage());
                throw e;
            } catch (Exception e) {
                log.error("Unexpected error fetching Jira incidents at startAt={}: {}", startAt, e.getMessage());
                break;
            }
        }

        log.info("Fetched {} Jira incidents from project {}", all.size(), jiraProperties.projectKey());
        return all;
    }
}
