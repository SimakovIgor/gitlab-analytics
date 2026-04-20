package io.simakov.analytics.web;

import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.security.WorkspaceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserAliasService {

    private final TrackedUserAliasRepository aliasRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final GitSourceRepository gitSourceRepository;
    private final EncryptionService encryptionService;
    private final GitLabApiClient gitLabApiClient;

    public void saveAlias(Long userId,
                          String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        String normalized = email.toLowerCase(Locale.ROOT).strip();
        if (!aliasRepository.existsByTrackedUserIdAndEmail(userId, normalized)) {
            aliasRepository.save(TrackedUserAlias.builder()
                .trackedUserId(userId)
                .email(normalized)
                .gitlabUserId(resolveGitlabUserId(normalized))
                .build());
        }
    }

    public void saveAliases(Long userId,
                            List<String> emails) {
        if (emails == null) {
            return;
        }
        for (String email : emails) {
            saveAlias(userId, email);
        }
    }

    /**
     * Явная привязка GitLab-аккаунта к пользователю.
     * Используется когда оператор вручную выбирает аккаунт из поиска GitLab.
     */
    public void linkGitlabAccount(Long userId,
                                  Long gitlabUserId,
                                  String username) {
        if (aliasRepository.existsByTrackedUserIdAndGitlabUserId(userId, gitlabUserId)) {
            return;
        }
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId)
            .gitlabUserId(gitlabUserId)
            .username(username)
            .build());
    }

    /**
     * Ищет GitLab user ID по username-части email через GitLab API.
     * Например, для "a.upatov@uzum.com" ищет username="a.upatov".
     * Возвращает null если проект ещё не настроен или username не найден.
     */
    private Long resolveGitlabUserId(String email) {
        if (!email.contains("@")) {
            return null;
        }
        if (!WorkspaceContext.isSet()) {
            return null;
        }
        Long workspaceId = WorkspaceContext.get();
        TrackedProject project = trackedProjectRepository
            .findTopByWorkspaceIdAndEnabledTrue(workspaceId)
            .orElse(null);
        if (project == null) {
            return null;
        }
        GitSource source = gitSourceRepository.findById(project.getGitSourceId()).orElse(null);
        if (source == null) {
            return null;
        }
        String token = encryptionService.decrypt(project.getTokenEncrypted());
        String username = email.substring(0, email.indexOf('@'));
        try {
            Long resolved = gitLabApiClient.findUserIdByUsername(source.getBaseUrl(), token, username).orElse(null);
            if (resolved == null) {
                log.debug("Could not resolve GitLab user ID for email {} (username={})", email, username);
            }
            return resolved;
        } catch (RuntimeException e) {
            log.warn("Failed to resolve GitLab user ID for email {} (username={}): {}", email, username, e.getMessage());
            return null;
        }
    }
}
