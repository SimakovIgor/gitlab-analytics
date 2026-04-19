package io.simakov.analytics.sync;

import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import io.simakov.analytics.domain.repository.TrackedUserRepository;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.gitlab.client.GitLabApiClient;
import io.simakov.analytics.gitlab.dto.GitLabUserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Обнаруживает GitLab placeholder-аккаунты (артефакты миграции вида "username_placeholder_xxx")
 * и автоматически привязывает их к существующим tracked users как дополнительные aliases.
 *
 * <p>GitLab создаёт placeholder-пользователя при миграции: все MR и коммиты, сделанные
 * до миграции, остаются записанными на placeholder-ID. Без этого шага исторические MR
 * не будут атрибутированы правильному разработчику.</p>
 *
 * <p>Стратегия сопоставления — только по имени:</p>
 * <ul>
 *   <li>"Placeholder Anton Lepikhin" → ищем TrackedUser с displayName="Anton Lepikhin"</li>
 *   <li>"Placeholder github Source User" → пропускаем (shared-заглушка, требует ручной привязки)</li>
 * </ul>
 *
 * <p>Сопоставление по email коммитов намеренно исключено: placeholder может быть shared
 * (разные авторы в разных проектах), но alias применяется глобально — это приводит к
 * неверной атрибуции MR. Ручная привязка через "Привязать GitLab" надёжнее.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceholderAliasDiscoveryService {

    private static final String PLACEHOLDER_MARKER = "_placeholder_";
    private static final String PLACEHOLDER_NAME_PREFIX = "Placeholder ";
    private static final int MAX_UNKNOWN_ID_LOOKUPS = 50;

    private final GitLabApiClient gitLabApiClient;
    private final MergeRequestRepository mergeRequestRepository;
    private final TrackedUserRepository trackedUserRepository;
    private final TrackedUserAliasRepository aliasRepository;
    private final TrackedProjectRepository trackedProjectRepository;
    private final GitSourceRepository gitSourceRepository;
    private final EncryptionService encryptionService;

    /**
     * Запускается асинхронно после добавления новых пользователей.
     * Перебирает все отслеживаемые проекты и запускает поиск placeholder-алиасов,
     * чтобы привязать placeholder-аккаунты к новым пользователям.
     */
    @Async("syncTaskExecutor")
    @SuppressWarnings("checkstyle:IllegalCatch")
    public void discoverForAllProjectsAsync() {
        List<TrackedProject> projects = trackedProjectRepository.findAll();
        var sourcesById = gitSourceRepository.findAll()
            .stream().collect(Collectors.toMap(GitSource::getId, s -> s));
        for (TrackedProject project : projects) {
            try {
                GitSource source = sourcesById.get(project.getGitSourceId());
                if (source == null) {
                    continue;
                }
                String token = encryptionService.decrypt(project.getTokenEncrypted());
                discoverAndSave(project.getWorkspaceId(), project.getId(), source.getBaseUrl(), token);
            } catch (Exception e) {
                log.error("Placeholder discovery failed for project={}: {}", project.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Запускается после синка проекта. Находит неизвестные author_gitlab_user_id в MR проекта,
     * проверяет — не placeholder ли это, и если да — пытается привязать по имени.
     */
    public void discoverAndSave(Long workspaceId,
                                Long trackedProjectId,
                                String baseUrl,
                                String token) {
        List<TrackedUser> workspaceUsers = trackedUserRepository.findAllByWorkspaceId(workspaceId);
        List<Long> userIds = workspaceUsers.stream().map(TrackedUser::getId).toList();
        Set<Long> knownIds = aliasRepository.findByTrackedUserIdIn(userIds).stream()
            .map(TrackedUserAlias::getGitlabUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        List<Long> unknownAuthorIds = mergeRequestRepository
            .findDistinctAuthorIdsByTrackedProjectId(trackedProjectId)
            .stream()
            .filter(id -> !knownIds.contains(id))
            .toList();

        if (unknownAuthorIds.isEmpty()) {
            return;
        }

        List<Long> lookupIds = unknownAuthorIds;
        if (unknownAuthorIds.size() > MAX_UNKNOWN_ID_LOOKUPS) {
            log.warn("Too many unknown author IDs ({}) for project={}, limiting lookup to {}",
                unknownAuthorIds.size(), trackedProjectId, MAX_UNKNOWN_ID_LOOKUPS);
            lookupIds = unknownAuthorIds.subList(0, MAX_UNKNOWN_ID_LOOKUPS);
        }

        List<TrackedUser> trackedUsers = workspaceUsers;

        for (Long unknownId : lookupIds) {
            GitLabUserDto user = gitLabApiClient.getUserById(baseUrl, token, unknownId);
            if (user == null || !isPlaceholder(user)) {
                continue;
            }

            Long matchedUserId = matchByName(user.name(), trackedUsers);

            if (matchedUserId != null) {
                aliasRepository.save(TrackedUserAlias.builder()
                    .trackedUserId(matchedUserId)
                    .gitlabUserId(unknownId)
                    .build());
                log.info("Auto-linked placeholder {} (id={}) → tracked user id={}",
                    user.username(), unknownId, matchedUserId);
            } else {
                log.debug("Could not match placeholder {} (id={}) by name — skipping (use manual link)",
                    user.username(), unknownId);
            }
        }
    }

    private boolean isPlaceholder(GitLabUserDto user) {
        return user.username() != null && user.username().contains(PLACEHOLDER_MARKER);
    }

    /**
     * "Placeholder Anton Lepikhin" → ищем TrackedUser с displayName="Anton Lepikhin" (case-insensitive).
     * "Placeholder github Source User" → null (shared-заглушка, требует ручной привязки).
     */
    private Long matchByName(String placeholderName,
                             List<TrackedUser> trackedUsers) {
        if (placeholderName == null || !placeholderName.startsWith(PLACEHOLDER_NAME_PREFIX)) {
            return null;
        }
        String realName = placeholderName.substring(PLACEHOLDER_NAME_PREFIX.length()).trim();
        if (realName.isBlank() || "github Source User".equalsIgnoreCase(realName)) {
            return null;
        }
        return trackedUsers.stream()
            .filter(u -> realName.equalsIgnoreCase(u.getDisplayName()))
            .map(TrackedUser::getId)
            .findFirst()
            .orElse(null);
    }
}
