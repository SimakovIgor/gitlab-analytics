package io.simakov.analytics.workspace;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.model.enums.WorkspaceRole;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import io.simakov.analytics.web.dto.MemberDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MembersServiceTest extends BaseIT {

    @Autowired
    private MembersService membersService;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceMemberRepository memberRepository;

    private Long ownerAppUserId;
    private Long guestAppUserId;

    @BeforeEach
    void setUp() {
        ownerAppUserId = appUserRepository.findAll().get(0).getId();

        AppUser guest = appUserRepository.save(AppUser.builder()
            .githubId(42L)
            .githubLogin("guest-member")
            .name("Guest User")
            .avatarUrl("https://avatars.githubusercontent.com/u/42")
            .lastLoginAt(Instant.now())
            .build());
        guestAppUserId = guest.getId();

        memberRepository.save(WorkspaceMember.builder()
            .workspaceId(testWorkspaceId)
            .appUserId(guestAppUserId)
            .role(WorkspaceRole.MEMBER.name())
            .build());
    }

    // ── listMembers ───────────────────────────────────────────────────────────

    @Test
    void listMembersReturnsAllWorkspaceMembers() {
        List<MemberDto> members = membersService.listMembers(testWorkspaceId);

        assertThat(members).hasSize(2);
    }

    @Test
    void listMembersPopulatesGitHubProfileData() {
        List<MemberDto> members = membersService.listMembers(testWorkspaceId);

        MemberDto guest = members.stream()
            .filter(m -> m.appUserId().equals(guestAppUserId))
            .findFirst()
            .orElseThrow();
        assertThat(guest.name()).isEqualTo("Guest User");
        assertThat(guest.username()).isEqualTo("guest-member");
        assertThat(guest.avatarUrl()).isEqualTo("https://avatars.githubusercontent.com/u/42");
        assertThat(guest.isOwner()).isFalse();
        assertThat(guest.joinedAt()).isNotBlank();
    }

    @Test
    void listMembersMarksOwnerCorrectly() {
        List<MemberDto> members = membersService.listMembers(testWorkspaceId);

        MemberDto owner = members.stream()
            .filter(m -> m.appUserId().equals(ownerAppUserId))
            .findFirst()
            .orElseThrow();
        assertThat(owner.isOwner()).isTrue();
        assertThat(owner.role()).isEqualTo(WorkspaceRole.OWNER.name());
    }

    @Test
    void listMembersFallsBackToAtLoginWhenNameIsNull() {
        AppUser noName = appUserRepository.save(AppUser.builder()
            .githubId(77L)
            .githubLogin("no-name-user")
            .lastLoginAt(Instant.now())
            .build());
        memberRepository.save(WorkspaceMember.builder()
            .workspaceId(testWorkspaceId)
            .appUserId(noName.getId())
            .role(WorkspaceRole.MEMBER.name())
            .build());

        List<MemberDto> members = membersService.listMembers(testWorkspaceId);

        MemberDto dto = members.stream()
            .filter(m -> m.appUserId().equals(noName.getId()))
            .findFirst()
            .orElseThrow();
        assertThat(dto.name()).isEqualTo("@no-name-user");
    }

    // ── removeMember ─────────────────────────────────────────────────────────

    @Test
    void removeMemberDeletesMembership() {
        membersService.removeMember(testWorkspaceId, guestAppUserId);

        assertThat(memberRepository.existsByWorkspaceIdAndAppUserId(testWorkspaceId, guestAppUserId)).isFalse();
    }

    @Test
    void removeMemberIsIdempotentForNonExistentUser() {
        // Should not throw
        membersService.removeMember(testWorkspaceId, 99999L);

        assertThat(memberRepository.findByWorkspaceId(testWorkspaceId)).hasSize(2);
    }

    @Test
    void removeMemberDoesNotAffectOtherWorkspaceMembers() {
        membersService.removeMember(testWorkspaceId, guestAppUserId);

        assertThat(memberRepository.existsByWorkspaceIdAndAppUserId(testWorkspaceId, ownerAppUserId)).isTrue();
    }
}
