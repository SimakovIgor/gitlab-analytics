package io.simakov.analytics.workspace;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.WorkspaceInvite;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.model.enums.WorkspaceRole;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceInviteRepository;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InviteServiceTest extends BaseIT {

    @Autowired
    private InviteService inviteService;

    @Autowired
    private WorkspaceInviteRepository inviteRepository;

    @Autowired
    private WorkspaceMemberRepository memberRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    private Long ownerAppUserId;
    private Long guestAppUserId;

    @BeforeEach
    void setUp() {
        ownerAppUserId = appUserRepository.findAll().get(0).getId();

        AppUser guest = appUserRepository.save(AppUser.builder()
            .githubId(99L)
            .githubLogin("guest-user")
            .lastLoginAt(Instant.now())
            .build());
        guestAppUserId = guest.getId();
    }

    // ── createInvite ─────────────────────────────────────────────────────────

    @Test
    void createInviteGeneratesUniqueToken() {
        WorkspaceInvite invite = inviteService.createInvite(testWorkspaceId, ownerAppUserId);

        assertThat(invite.getId()).isNotNull();
        assertThat(invite.getToken()).isNotBlank().hasSize(32);
        assertThat(invite.getWorkspaceId()).isEqualTo(testWorkspaceId);
        assertThat(invite.getCreatedBy()).isEqualTo(ownerAppUserId);
        assertThat(invite.getRole()).isEqualTo(WorkspaceRole.MEMBER.name());
        assertThat(invite.getExpiresAt()).isAfter(Instant.now().plus(6, ChronoUnit.DAYS));
        assertThat(invite.getUsedAt()).isNull();
    }

    @Test
    void createInvitePersistsToDatabase() {
        WorkspaceInvite invite = inviteService.createInvite(testWorkspaceId, ownerAppUserId);

        Optional<WorkspaceInvite> found = inviteRepository.findByToken(invite.getToken());
        assertThat(found).isPresent();
        assertThat(found.get().getWorkspaceId()).isEqualTo(testWorkspaceId);
    }

    @Test
    void createTwoInvitesProduceDifferentTokens() {
        WorkspaceInvite first = inviteService.createInvite(testWorkspaceId, ownerAppUserId);
        WorkspaceInvite second = inviteService.createInvite(testWorkspaceId, ownerAppUserId);

        assertThat(first.getToken()).isNotEqualTo(second.getToken());
    }

    // ── findValid ─────────────────────────────────────────────────────────────

    @Test
    void findValidReturnsPresentForFreshInvite() {
        WorkspaceInvite invite = inviteService.createInvite(testWorkspaceId, ownerAppUserId);

        Optional<WorkspaceInvite> found = inviteService.findValid(invite.getToken());

        assertThat(found).isPresent();
    }

    @Test
    void findValidReturnsEmptyForUnknownToken() {
        Optional<WorkspaceInvite> found = inviteService.findValid("does-not-exist");

        assertThat(found).isEmpty();
    }

    @Test
    void findValidReturnsEmptyForExpiredInvite() {
        WorkspaceInvite invite = inviteRepository.save(WorkspaceInvite.builder()
            .workspaceId(testWorkspaceId)
            .token("expired-token-001")
            .role(WorkspaceRole.MEMBER.name())
            .createdBy(ownerAppUserId)
            .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .build());

        Optional<WorkspaceInvite> found = inviteService.findValid(invite.getToken());

        assertThat(found).isEmpty();
    }

    @Test
    void findValidReturnsEmptyForAlreadyUsedInvite() {
        WorkspaceInvite invite = inviteRepository.save(WorkspaceInvite.builder()
            .workspaceId(testWorkspaceId)
            .token("used-token-001")
            .role(WorkspaceRole.MEMBER.name())
            .createdBy(ownerAppUserId)
            .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
            .usedAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .usedByAppUserId(guestAppUserId)
            .build());

        Optional<WorkspaceInvite> found = inviteService.findValid(invite.getToken());

        assertThat(found).isEmpty();
    }

    // ── consumeInvite ─────────────────────────────────────────────────────────

    @Test
    void consumeInviteCreatesMembershipAndMarksTokenUsed() {
        WorkspaceInvite invite = inviteService.createInvite(testWorkspaceId, ownerAppUserId);

        inviteService.consumeInvite(invite, guestAppUserId);

        assertThat(memberRepository.existsByWorkspaceIdAndAppUserId(testWorkspaceId, guestAppUserId)).isTrue();

        WorkspaceInvite updated = inviteRepository.findById(invite.getId()).orElseThrow();
        assertThat(updated.getUsedAt()).isNotNull();
        assertThat(updated.getUsedByAppUserId()).isEqualTo(guestAppUserId);
    }

    @Test
    void consumeInviteAssignsMemberRole() {
        WorkspaceInvite invite = inviteService.createInvite(testWorkspaceId, ownerAppUserId);
        inviteService.consumeInvite(invite, guestAppUserId);

        WorkspaceMember member = memberRepository
            .findByWorkspaceIdAndAppUserId(testWorkspaceId, guestAppUserId)
            .orElseThrow();
        assertThat(member.getRole()).isEqualTo(WorkspaceRole.MEMBER.name());
    }

    @Test
    void consumeInviteIsIdempotentForExistingMember() {
        WorkspaceInvite invite = inviteService.createInvite(testWorkspaceId, ownerAppUserId);

        // owner is already a member — consume should not fail or create duplicate
        inviteService.consumeInvite(invite, ownerAppUserId);

        long memberCount = memberRepository.findByWorkspaceId(testWorkspaceId).stream()
            .filter(m -> m.getAppUserId().equals(ownerAppUserId))
            .count();
        assertThat(memberCount).isEqualTo(1);
    }

    @Test
    void consumeInviceTwiceWithSameUserDoesNotCreateDuplicate() {
        WorkspaceInvite invite = inviteService.createInvite(testWorkspaceId, ownerAppUserId);

        inviteService.consumeInvite(invite, guestAppUserId);
        inviteService.consumeInvite(invite, guestAppUserId);

        long count = memberRepository.findByWorkspaceId(testWorkspaceId).stream()
            .filter(m -> m.getAppUserId().equals(guestAppUserId))
            .count();
        assertThat(count).isEqualTo(1);
    }
}
