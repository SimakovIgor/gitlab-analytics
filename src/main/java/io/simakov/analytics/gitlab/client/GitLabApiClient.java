package io.simakov.analytics.gitlab.client;

import io.simakov.analytics.api.exception.GitLabApiException;
import io.simakov.analytics.config.AppProperties;
import io.simakov.analytics.gitlab.dto.GitLabApprovalsDto;
import io.simakov.analytics.gitlab.dto.GitLabCommitDto;
import io.simakov.analytics.gitlab.dto.GitLabDiscussionDto;
import io.simakov.analytics.gitlab.dto.GitLabMergeRequestDto;
import io.simakov.analytics.gitlab.dto.GitLabProjectDto;
import io.simakov.analytics.gitlab.dto.GitLabUserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class GitLabApiClient {

    private static final String PRIVATE_TOKEN_HEADER = "PRIVATE-TOKEN";

    private final WebClient webClient;
    private final AppProperties appProperties;

    /**
     * Verify connectivity by fetching the authenticated user.
     */
    public GitLabUserDto getCurrentUser(String baseUrl,
                                        String token) {
        log.debug("Testing GitLab connectivity at {}", baseUrl);
        return webClient.get()
            .uri(baseUrl + "/api/v4/user")
            .header(PRIVATE_TOKEN_HEADER, token)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                response.bodyToMono(String.class)
                    .map(body -> new GitLabApiException("GitLab API error " + response.statusCode(), response.statusCode())))
            .bodyToMono(GitLabUserDto.class)
            .block(readTimeout());
    }

    /**
     * Search GitLab projects accessible to the token owner.
     * Results are filtered to projects the token has membership in.
     */
    public List<GitLabProjectDto> searchProjects(String baseUrl,
                                                 String token,
                                                 String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String extraParams = "&search=" + encoded + "&membership=true&order_by=last_activity_at&sort=desc";
        return fetchAllPages(baseUrl, token, "/api/v4/projects", GitLabProjectDto[].class, extraParams);
    }

    /**
     * Fetch all merge requests for a project updated after the given date.
     * Uses updated_after to capture MRs with activity in the window, even if created earlier.
     */
    public List<GitLabMergeRequestDto> getMergeRequests(String baseUrl,
                                                        String token,
                                                        Long gitlabProjectId,
                                                        Instant dateFrom,
                                                        Instant dateTo) {
        String path = "/api/v4/projects/" + gitlabProjectId + "/merge_requests";
        String updatedAfter = DateTimeFormatter.ISO_INSTANT.format(dateFrom);
        String updatedBefore = DateTimeFormatter.ISO_INSTANT.format(dateTo);

        log.info("Fetching MRs for project {} updated between {} and {}", gitlabProjectId, updatedAfter, updatedBefore);

        return fetchAllPages(baseUrl, token, path,
            GitLabMergeRequestDto[].class,
            "&state=all&updated_after=" + updatedAfter + "&updated_before=" + updatedBefore);
    }

    /**
     * Fetch all commits for an MR.
     */
    public List<GitLabCommitDto> getMergeRequestCommits(String baseUrl,
                                                        String token,
                                                        Long gitlabProjectId,
                                                        Long mrIid) {
        String path = "/api/v4/projects/" + gitlabProjectId + "/merge_requests/" + mrIid + "/commits";
        return fetchAllPages(baseUrl, token, path, GitLabCommitDto[].class, "");
    }

    /**
     * Fetch all discussions (threads with notes) for an MR.
     */
    public List<GitLabDiscussionDto> getMergeRequestDiscussions(String baseUrl,
                                                                String token,
                                                                Long gitlabProjectId,
                                                                Long mrIid) {
        String path = "/api/v4/projects/" + gitlabProjectId + "/merge_requests/" + mrIid + "/discussions";
        return fetchAllPages(baseUrl, token, path, GitLabDiscussionDto[].class, "");
    }

    /**
     * Fetch a single commit with diff statistics (additions, deletions).
     * The MR commits list endpoint does not include stats — this per-commit call is required.
     */
    public GitLabCommitDto getCommitWithStats(String baseUrl,
                                              String token,
                                              Long gitlabProjectId,
                                              String sha) {
        String url = baseUrl + "/api/v4/projects/" + gitlabProjectId + "/repository/commits/" + sha;
        log.debug("Fetching commit stats: GET {}", url);
        return webClient.get()
            .uri(url)
            .header(PRIVATE_TOKEN_HEADER, token)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                response.bodyToMono(String.class)
                    .map(body -> new GitLabApiException("Commit stats error " + response.statusCode(), response.statusCode())))
            .bodyToMono(GitLabCommitDto.class)
            .retryWhen(retrySpec())
            .block(readTimeout());
    }

    /**
     * Fetch approvals for an MR.
     * GitLab approvals API may return 403 if not available on the plan — callers should handle gracefully.
     */
    public GitLabApprovalsDto getMergeRequestApprovals(String baseUrl,
                                                       String token,
                                                       Long gitlabProjectId,
                                                       Long mrIid) {
        String url = baseUrl + "/api/v4/projects/" + gitlabProjectId + "/merge_requests/" + mrIid + "/approvals";
        log.debug("Fetching approvals: GET {}", url);
        return webClient.get()
            .uri(url)
            .header(PRIVATE_TOKEN_HEADER, token)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response ->
                response.bodyToMono(String.class)
                    .map(body -> new GitLabApiException("Approvals API error " + response.statusCode(), response.statusCode())))
            .bodyToMono(GitLabApprovalsDto.class)
            .retryWhen(retrySpec())
            .block(readTimeout());
    }

    // -----------------------------------------------------------------------
    // Pagination
    // -----------------------------------------------------------------------

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private <T> List<T> fetchAllPages(String baseUrl,
                                      String token,
                                      String path,
                                      Class<T[]> responseType,
                                      String extraParams) {
        List<T> all = new ArrayList<>();
        int page = 1;

        while (true) {
            String url = baseUrl + path + "?page=" + page + "&per_page=" + appProperties.gitlab().perPage() + extraParams;
            log.debug("GET {}", url);

            ResponseEntity<T[]> response = webClient.get()
                .uri(url)
                .header(PRIVATE_TOKEN_HEADER, token)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                    resp.bodyToMono(String.class)
                        .map(body -> new GitLabApiException("GitLab API error " + resp.statusCode() + ": " + body, resp.statusCode())))
                .toEntity(responseType)
                .retryWhen(retrySpec())
                .block(readTimeout());

            if (response == null || response.getBody() == null || response.getBody().length == 0) {
                break;
            }

            Collections.addAll(all, response.getBody());
            log.debug("Fetched {} items (page {}), total so far: {}", response.getBody().length, page, all.size());

            String nextPage = response.getHeaders().getFirst("X-Next-Page");
            if (nextPage == null || nextPage.isBlank()) {
                break;
            }

            page = Integer.parseInt(nextPage);
        }

        return all;
    }

    private Retry retrySpec() {
        int maxRetries = appProperties.gitlab().maxRetries();
        return Retry.backoff(maxRetries, Duration.ofSeconds(2))
            .filter(e -> e instanceof GitLabApiException && ((GitLabApiException) e).isTransient())
            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                new GitLabApiException("GitLab API failed after " + maxRetries + " retries: " + retrySignal.failure().getMessage()));
    }

    private Duration readTimeout() {
        return Duration.ofSeconds(appProperties.gitlab().readTimeoutSeconds());
    }
}
