package io.simakov.analytics.domain.repository;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackedUserAliasRepositoryTest extends BaseIT {

    @Autowired
    private TrackedUserAliasRepository aliasRepository;

    @Autowired
    private TrackedUserRepository trackedUserRepository;

    private Long userId1;
    private Long userId2;

    @BeforeEach
    void setUp() {
        TrackedUser user1 = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Alice").email("alice@example.com").enabled(true).build());
        userId1 = user1.getId();

        TrackedUser user2 = trackedUserRepository.save(TrackedUser.builder()
            .workspaceId(testWorkspaceId)
            .displayName("Bob").email("bob@example.com").enabled(true).build());
        userId2 = user2.getId();
    }

    @Test
    void findByGitlabUserIdInReturnMatchingAliases() {
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId1).gitlabUserId(101L).email("alice@example.com").build());
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId2).gitlabUserId(202L).email("bob@example.com").build());

        List<TrackedUserAlias> result = aliasRepository.findByGitlabUserIdIn(List.of(101L, 202L));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TrackedUserAlias::getGitlabUserId)
            .containsExactlyInAnyOrder(101L, 202L);
    }

    @Test
    void findByGitlabUserIdInReturnsOnlyRequestedIds() {
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId1).gitlabUserId(101L).email("alice@example.com").build());
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId2).gitlabUserId(202L).email("bob@example.com").build());

        List<TrackedUserAlias> result = aliasRepository.findByGitlabUserIdIn(List.of(101L));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getTrackedUserId()).isEqualTo(userId1);
    }

    @Test
    void findByGitlabUserIdInReturnsEmptyWhenNoMatch() {
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId1).gitlabUserId(101L).email("alice@example.com").build());

        List<TrackedUserAlias> result = aliasRepository.findByGitlabUserIdIn(List.of(99_999L));

        assertThat(result).isEmpty();
    }

    @Test
    void findByGitlabUserIdInIgnoresAliasesWithNullGitlabUserId() {
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId1).gitlabUserId(null).email("alice@example.com").build());

        List<TrackedUserAlias> result = aliasRepository.findByGitlabUserIdIn(List.of(101L));

        assertThat(result).isEmpty();
    }

    @Test
    void findByGitlabUserIdInReturnsEmptyForEmptyInput() {
        aliasRepository.save(TrackedUserAlias.builder()
            .trackedUserId(userId1).gitlabUserId(101L).email("alice@example.com").build());

        List<TrackedUserAlias> result = aliasRepository.findByGitlabUserIdIn(List.of());

        assertThat(result).isEmpty();
    }
}
