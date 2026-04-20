package io.simakov.analytics.gitlab.client;

import io.simakov.analytics.api.exception.GitLabApiException;
import io.simakov.analytics.config.AppProperties;
import io.simakov.analytics.gitlab.dto.GitLabApprovalsDto;
import io.simakov.analytics.gitlab.dto.GitLabCommitDto;
import io.simakov.analytics.gitlab.dto.GitLabDiscussionDto;
import io.simakov.analytics.gitlab.dto.GitLabMergeRequestDto;
import io.simakov.analytics.gitlab.dto.GitLabMrDiffFileDto;
import io.simakov.analytics.gitlab.dto.GitLabProjectDto;
import io.simakov.analytics.gitlab.dto.GitLabUserDto;
import io.simakov.analytics.gitlab.dto.GitLabUserSearchDto;
import io.simakov.analytics.gitlab.dto.MrNetDiffStats;
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
     * Fetches a GitLab user by ID. Returns null if the user does not exist or on API error.
     */
    public GitLabUserDto getUserById(String baseUrl,
                                     String token,
                                     Long userId) {
        try {
            return webClient.get()
                .uri(baseUrl + "/api/v4/users/" + userId)
                .header(PRIVATE_TOKEN_HEADER, token)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                    response.bodyToMono(String.class)
                        .map(body -> new GitLabApiException("GitLab API error " + response.statusCode(), response.statusCode())))
                .bodyToMono(GitLabUserDto.class)
                .block(readTimeout());
        } catch (GitLabApiException e) {
            log.warn("Could not fetch GitLab user id={}: {}", userId, e.getMessage());
            return null;
        }
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
     * Search GitLab users by name or username.
     * Returns up to one page of results. public_email is populated only if the user has set it.
     */
    public List<GitLabUserSearchDto> searchUsers(String baseUrl,
                                                 String token,
                                                 String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return fetchAllPages(baseUrl, token, "/api/v4/users",
            GitLabUserSearchDto[].class, "&search=" + encoded + "&active=true");
    }

    /**
     * Find a GitLab user ID by exact username match.
     * Uses {@code GET /api/v4/users?username=...} which returns only exact matches.
     * Returns empty if no user with that username exists.
     */
    public java.util.Optional<Long> findUserIdByUsername(String baseUrl,
                                                         String token,
                                                         String username) {
        String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
        List<GitLabUserSearchDto> users = fetchAllPages(baseUrl, token, "/api/v4/users",
            GitLabUserSearchDto[].class, "&username=" + encoded);
        return users.stream()
            .filter(u -> username.equalsIgnoreCase(u.username()))
            .map(GitLabUserSearchDto::id)
            .findFirst();
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
            .block(blockTimeout());
    }

    /**
     * Fetch net diff stats for an MR by parsing the diffs endpoint.
     * Matches what GitLab UI shows on the Changes tab (net diff vs base branch).
     * Files with too_large flag contribute 0 to both counters.
     */
    public MrNetDiffStats getMrNetDiffStats(String baseUrl,
                                            String token,
                                            Long gitlabProjectId,
                                            Long mrIid) {
        String path = "/api/v4/projects/" + gitlabProjectId + "/merge_requests/" + mrIid + "/diffs";
        List<GitLabMrDiffFileDto> diffs = fetchAllPages(baseUrl, token, path, GitLabMrDiffFileDto[].class, "");
        int additions = 0;
        int deletions = 0;
        for (GitLabMrDiffFileDto file : diffs) {
            if (file.tooLarge() || file.diff() == null) {
                continue;
            }
            for (String line : file.diff().split("\n", -1)) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    additions++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    deletions++;
                }
            }
        }
        return new MrNetDiffStats(additions, deletions);
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
            .block(blockTimeout());
    }

    // -----------------------------------------------------------------------
    // Pagination
    // -----------------------------------------------------------------------

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
                .block(blockTimeout());

            if (response == null || response.getBody() == null || response.getBody().length == 0) {
                break;
            }

            Collections.addAll(all, response.getBody());
            log.debug("Fetched {} items (page {}), total so far: {}", response.getBody().length, page, all.size());

            String nextPage = response.getHeaders().getFirst("X-Next-Page");
            if (nextPage == null || nextPage.isBlank()) {
                break;
            }
            try {
                page = Integer.parseInt(nextPage);
            } catch (NumberFormatException e) {
                log.warn("Unexpected X-Next-Page header value '{}', stopping pagination", nextPage);
                break;
            }
        }

        return all;
    }

    private Retry retrySpec() {
        int maxRetries = appProperties.gitlab().maxRetries();
        int backoffSeconds = appProperties.gitlab().retryBackoffSeconds();
        int maxBackoffSeconds = appProperties.gitlab().maxBackoffSeconds();
        return Retry.backoff(maxRetries, Duration.ofSeconds(backoffSeconds))
            .maxBackoff(Duration.ofSeconds(maxBackoffSeconds))
            .jitter(1.0)
            .filter(e -> e instanceof GitLabApiException && ((GitLabApiException) e).isTransient())
            .doBeforeRetry(signal -> {
                Throwable failure = signal.failure();
                int attempt = (int) signal.totalRetries() + 1;
                if (failure instanceof GitLabApiException glEx
                        && glEx.getStatusCode() != null
                        && glEx.getStatusCode().value() == 429) {
                    log.warn("GitLab rate limit (429) hit, retry attempt {}/{}", attempt, maxRetries);
                } else {
                    log.warn("GitLab transient error ({}), retry attempt {}/{}: {}",
                        failure instanceof GitLabApiException glEx2 && glEx2.getStatusCode() != null
                            ? glEx2.getStatusCode().value() : "?",
                        attempt, maxRetries, failure.getMessage());
                }
            })
            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                new GitLabApiException("GitLab API failed after " + maxRetries + " retries: " + retrySignal.failure().getMessage()));
    }

    private Duration readTimeout() {
        return Duration.ofSeconds(appProperties.gitlab().readTimeoutSeconds());
    }

    private Duration blockTimeout() {
        return Duration.ofSeconds(appProperties.gitlab().blockTimeoutSeconds());
    }
}
