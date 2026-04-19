package io.simakov.analytics.domain.repository;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class TrackedUserAliasRepositoryTest extends BaseIT {

    @Autowired
    private TrackedUserAliasRepository aliasRepository;

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    private Long userId1;

    @BeforeEach
    void setUp() {
        TrackedUser user1 = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Alice").email("alice@example.com").enabled(true).build());
        userId1 = user1.getId();
    }

    @Test
    void existsByTrackedUserIdAndEmailReturnsTrueWhenAliasExists() {
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId1).gitlabUserId(101L).email("alice@example.com").build());

        assertThat(aliasRepository.existsByTrackedUserIdAndEmail(userId1, "alice@example.com")).isTrue();
    }

    @Test
    void existsByTrackedUserIdAndEmailReturnsFalseWhenAliasAbsent() {
        assertThat(aliasRepository.existsByTrackedUserIdAndEmail(userId1, "unknown@example.com")).isFalse();
    }

    @Test
    void existsByGitlabUserIdReturnsTrueWhenExists() {
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId1).gitlabUserId(101L).email("alice@example.com").build());

        assertThat(aliasRepository.existsByGitlabUserId(101L)).isTrue();
    }

    @Test
    void existsByGitlabUserIdReturnsFalseWhenAbsent() {
        assertThat(aliasRepository.existsByGitlabUserId(99_999L)).isFalse();
    }
}
