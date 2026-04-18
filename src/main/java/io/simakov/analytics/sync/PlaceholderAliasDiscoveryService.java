package io.simakov.analytics.sync;

import io.simakov.analytics.domain.model.GitSource;
import io.simakov.analytics.domain.model.MergeRequest;
import io.simakov.analytics.domain.model.MergeRequestCommit;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.MergeRequestCommitRepository;
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
import java.util.Locale;
import java.util.Map;
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
 * <p>Стратегия сопоставления:</p>
 * <ol>
 *   <li>По имени: "Placeholder Anton Lepikhin" → ищем TrackedUser с displayName="Anton Lepikhin"</li>
 *   <li>По email коммитов: если у MR-автора-placeholder'а в коммитах email совпадает
 *       с email из tracked_user_alias — привязываем к этому пользователю.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceholderAliasDiscoveryService {

    private static final String PLACEHOLDER_MARKER = "_placeholder_";
    private static final String PLACEHOLDER_NAME_PREFIX = "Placeholder ";

    private final GitLabApiClient gitLabApiClient;
    private final MergeRequestRepository mergeRequestRepository;
    private final MergeRequestCommitRepository commitRepository;
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
    public void discoverForAllProjectsAsync() {
        List<TrackedProject> projects = trackedProjectRepository.findAll();
        for (TrackedProject project : projects) {
            GitSource source = gitSourceRepository.findById(project.getGitSourceId()).orElse(null);
            if (source == null) {
                continue;
            }
            String token = encryptionService.decrypt(project.getTokenEncrypted());
            discoverAndSave(project.getId(), source.getBaseUrl(), token);
        }
    }

    /**
     * Запускается после синка проекта. Находит неизвестные author_gitlab_user_id в MR проекта,
     * проверяет — не placeholder ли это, и если да — привязывает к нужному tracked user.
     */
    public void discoverAndSave(Long trackedProjectId,
                                String baseUrl,
                                String token) {
        Set<Long> knownIds = aliasRepository.findAll().stream()
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

        List<TrackedUser> trackedUsers = trackedUserRepository.findAll();
        Map<String, Long> emailToTrackedUserId = buildEmailToUserIdMap(trackedUsers);

        for (Long unknownId : unknownAuthorIds) {
            GitLabUserDto user = gitLabApiClient.getUserById(baseUrl, token, unknownId);
            if (user == null || !isPlaceholder(user)) {
                continue;
            }

            Long matchedUserId = matchByName(user.name(), trackedUsers);
            if (matchedUserId == null && isPersonalPlaceholderName(user.name())) {
                matchedUserId = matchByCommitEmails(unknownId, trackedProjectId, emailToTrackedUserId);
            }

            if (matchedUserId != null) {
                aliasRepository.save(TrackedUserAlias.builder()
                    .trackedUserId(matchedUserId)
                    .gitlabUserId(unknownId)
                    .build());
                log.info("Auto-linked placeholder {} (id={}) → tracked user id={}",
                    user.username(), unknownId, matchedUserId);
            } else {
                log.debug("Could not match placeholder {} (id={}) to any tracked user",
                    user.username(), unknownId);
            }
        }
    }

    private boolean isPlaceholder(GitLabUserDto user) {
        return user.username() != null && user.username().contains(PLACEHOLDER_MARKER);
    }

    /**
     * Возвращает true только для «личных» placeholder-аккаунтов вида "Placeholder Anton Lepikhin".
     * Generic GitHub-заглушки ("Placeholder github Source User") не подходят для email-fallback,
     * т.к. их MRы содержат коммиты всей команды — сопоставление даст ложный результат.
     */
    private boolean isPersonalPlaceholderName(String name) {
        if (name == null || !name.startsWith(PLACEHOLDER_NAME_PREFIX)) {
            return false;
        }
        String realName = name.substring(PLACEHOLDER_NAME_PREFIX.length()).trim();
        return !realName.isBlank() && !"github Source User".equalsIgnoreCase(realName);
    }

    /**
     * "Placeholder Anton Lepikhin" → ищем TrackedUser с displayName="Anton Lepikhin" (case-insensitive).
     * "Placeholder github Source User" → null (не поддаётся сопоставлению по имени).
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

    /**
     * Если сопоставление по имени не удалось — смотрим email'ы коммитов, которые
     * пользователь пушил в MR этого проекта. Если email совпадает с алиасом — нашли.
     */
    private Long matchByCommitEmails(Long unknownAuthorId,
                                     Long trackedProjectId,
                                     Map<String, Long> emailToTrackedUserId) {
        List<MergeRequest> authorMrs = mergeRequestRepository
            .findDistinctAuthorIdsByTrackedProjectId(trackedProjectId)
            .stream()
            .filter(id -> id.equals(unknownAuthorId))
            .findFirst()
            .map(id -> mergeRequestRepository.findAll().stream()
                .filter(mr -> id.equals(mr.getAuthorGitlabUserId())
                    && trackedProjectId.equals(mr.getTrackedProjectId()))
                .toList())
            .orElse(List.of());

        List<Long> mrIds = authorMrs.stream().map(MergeRequest::getId).toList();
        if (mrIds.isEmpty()) {
            return null;
        }

        return commitRepository.findByMergeRequestIdIn(mrIds).stream()
            .map(MergeRequestCommit::getAuthorEmail)
            .filter(Objects::nonNull)
            .map(e -> e.toLowerCase(Locale.ROOT).strip())
            .map(emailToTrackedUserId::get)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    private Map<String, Long> buildEmailToUserIdMap(List<TrackedUser> trackedUsers) {
        List<Long> userIds = trackedUsers.stream().map(TrackedUser::getId).toList();
        return aliasRepository.findByTrackedUserIdIn(userIds).stream()
            .filter(a -> a.getEmail() != null)
            .collect(Collectors.toMap(
                a -> a.getEmail().toLowerCase(Locale.ROOT),
                TrackedUserAlias::getTrackedUserId,
                (existing, replacement) -> existing
            ));
    }
}
