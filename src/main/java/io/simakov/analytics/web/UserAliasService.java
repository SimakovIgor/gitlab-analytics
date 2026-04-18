package io.simakov.analytics.web;

import io.simakov.analytics.domain.model.TrackedUserAlias;
import io.simakov.analytics.domain.repository.TrackedUserAliasRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UserAliasService {

    private final TrackedUserAliasRepository aliasRepository;

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
        if (aliasRepository.existsByGitlabUserId(gitlabUserId)) {
            return;
        }
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId)
            .gitlabUserId(gitlabUserId)
            .username(username)
            .build());
    }
}
