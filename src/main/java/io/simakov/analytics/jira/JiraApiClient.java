package io.simakov.analytics.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.simakov.analytics.jira.dto.JiraIssueDto;
import io.simakov.analytics.jira.dto.JiraIssueDto.Fields;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@org.springframework.stereotype.Component
public class JiraApiClient {

    private static final DateTimeFormatter JIRA_DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final int PAGE_SIZE = 100;

    private final WebClient webClient;
    private final JiraProperties jiraProperties;
    private final ObjectMapper objectMapper;

    public JiraApiClient(JiraProperties jiraProperties,
                         ObjectMapper objectMapper) {
        this.jiraProperties = jiraProperties;
        this.objectMapper = objectMapper;
        String credentials = jiraProperties.username() + ":" + jiraProperties.apiToken();
        String basicAuth = java.util.Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8));

        this.webClient = WebClient.builder()
            .baseUrl(jiraProperties.baseUrl())
            .defaultHeader("Authorization", "Basic " + basicAuth)
            .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
            .build();
    }

    private static String textOrNull(JsonNode node,
                                     String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static OffsetDateTime dateTimeOrNull(JsonNode node,
                                                 String field) {
        String text = textOrNull(node, field);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            log.debug("Failed to parse datetime field '{}' value '{}': {}", field, text, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches all Jira incidents from the configured project created on or after {@code since}.
     * Paginates automatically until all matching issues are retrieved.
     * Extracts custom fields for impact start/end times when configured.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    public List<JiraIssueDto> fetchIncidents(Instant since) {
        String jql = "project = \"" + jiraProperties.projectKey()
            + "\" AND issuetype = Incident AND created >= \""
            + JIRA_DATE_FMT.format(since) + "\"";
        String fields = buildFieldsList();

        log.info("Jira search: {}", jql);

        List<JiraIssueDto> all = new ArrayList<>();
        int startAt = 0;
        int maxResults = Math.min(PAGE_SIZE, jiraProperties.maxResults());

        while (true) {
            int fetched = fetchPage(jql, fields, startAt, maxResults, all);
            if (fetched <= 0) {
                break;
            }
            startAt += fetched;
        }

        log.info("Fetched {} Jira incidents from project {}", all.size(), jiraProperties.projectKey());
        return all;
    }

    /**
     * Fetches one page and appends parsed issues to {@code accumulator}.
     *
     * @return number of issues fetched, or -1 to stop pagination
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private int fetchPage(String jql,
                          String fields,
                          int startAt,
                          int maxResults,
                          List<JiraIssueDto> accumulator) {
        Duration timeout = Duration.ofSeconds(jiraProperties.readTimeoutSeconds());
        try {
            String rawJson = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/rest/api/2/search")
                    .queryParam("jql", jql)
                    .queryParam("fields", fields)
                    .queryParam("startAt", startAt)
                    .queryParam("maxResults", maxResults)
                    .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                    response.bodyToMono(String.class)
                        .map(body -> new JiraApiException(
                            "Jira API error " + response.statusCode() + ": " + body)))
                .bodyToMono(String.class)
                .block(timeout);

            if (rawJson == null) {
                return -1;
            }
            return parsePage(rawJson, startAt, accumulator);
        } catch (JiraApiException e) {
            log.error("Jira API error during incident fetch: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error fetching Jira incidents at startAt={}: {}", startAt, e.getMessage());
            return -1;
        }
    }

    private int parsePage(String rawJson,
                          int startAt,
                          List<JiraIssueDto> accumulator)
        throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = objectMapper.readTree(rawJson);
        JsonNode issuesNode = root.get("issues");
        int total = root.has("total")
            ? root.get("total").asInt()
            : 0;

        if (issuesNode == null || !issuesNode.isArray() || issuesNode.isEmpty()) {
            return -1;
        }

        for (JsonNode issueNode : issuesNode) {
            accumulator.add(parseIssue(issueNode));
        }

        log.debug("Jira page startAt={}, fetched={}, total={}", startAt, issuesNode.size(), total);
        return accumulator.size() >= total
            ? -1
            : issuesNode.size();
    }

    private String buildFieldsList() {
        String base = "summary,created,resolutiondate,components";
        if (!hasImpactFields()) {
            return base;
        }
        return base + "," + jiraProperties.impactStartFieldId()
            + "," + jiraProperties.impactEndFieldId();
    }

    private boolean hasImpactFields() {
        return jiraProperties.impactStartFieldId() != null
            && !jiraProperties.impactStartFieldId().isBlank()
            && jiraProperties.impactEndFieldId() != null
            && !jiraProperties.impactEndFieldId().isBlank();
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private JiraIssueDto parseIssue(JsonNode issueNode) {
        String key = issueNode.has("key")
            ? issueNode.get("key").asText()
            : null;
        JsonNode fieldsNode = issueNode.get("fields");

        String summary = textOrNull(fieldsNode, "summary");
        OffsetDateTime created = dateTimeOrNull(fieldsNode, "created");
        OffsetDateTime resolutiondate = dateTimeOrNull(fieldsNode, "resolutiondate");
        List<JiraIssueDto.Component> components = parseComponents(fieldsNode);

        OffsetDateTime impactStart = null;
        OffsetDateTime impactEnd = null;
        if (hasImpactFields()) {
            impactStart = dateTimeOrNull(fieldsNode, jiraProperties.impactStartFieldId());
            impactEnd = dateTimeOrNull(fieldsNode, jiraProperties.impactEndFieldId());
        }

        return new JiraIssueDto(key, new Fields(summary, created, resolutiondate, components, impactStart, impactEnd));
    }

    private List<JiraIssueDto.Component> parseComponents(JsonNode fieldsNode) {
        List<JiraIssueDto.Component> components = new ArrayList<>();
        if (fieldsNode == null || !fieldsNode.has("components")) {
            return components;
        }
        JsonNode arr = fieldsNode.get("components");
        if (arr.isArray()) {
            for (JsonNode c : arr) {
                if (c.has("name")) {
                    components.add(new JiraIssueDto.Component(c.get("name").asText()));
                }
            }
        }
        return components;
    }
}
