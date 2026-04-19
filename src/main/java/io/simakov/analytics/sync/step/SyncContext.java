package io.simakov.analytics.sync.step;

/**
 * Immutable GitLab connection context passed to each {@link SyncStep}.
 *
 * @param baseUrl         GitLab instance base URL
 * @param token           decrypted access token for the project
 * @param gitlabProjectId GitLab's own numeric project ID (not our DB ID)
 */
public record SyncContext(String baseUrl, String token, Long gitlabProjectId) {
}
