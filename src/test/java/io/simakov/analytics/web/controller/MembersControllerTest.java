package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.model.WorkspaceInvite;
import io.simakov.analytics.domain.model.WorkspaceMember;
import io.simakov.analytics.domain.model.enums.WorkspaceRole;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.WorkspaceInviteRepository;
import io.simakov.analytics.domain.repository.WorkspaceMemberRepository;
import io.simakov.analytics.security.AppUserPrincipal;
import io.simakov.analytics.workspace.InviteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class MembersControllerTest extends BaseIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private WorkspaceMemberRepository memberRepository;

    @Autowired
    private WorkspaceInviteRepository inviteRepository;

    @Autowired
    private InviteService inviteService;

    private AppUser owner;
    private AppUser guest;
    private OAuth2AuthenticationToken ownerAuth;

    @BeforeEach
    void setUp() {
        owner = appUserRepository.findAll().get(0);

        guest = appUserRepository.save(AppUser.builder()
            .githubId(55L)
            .githubLogin("guest-ctrl")
            .name("Guest Ctrl")
            .lastLoginAt(Instant.now())
            .build());

        ownerAuth = oauthToken(owner);
    }

    // ── POST /settings/members/invite ─────────────────────────────────────────

    @Test
    void createInviteReturns201WithLink() throws Exception {
        mockMvc.perform(post("/settings/members/invite")
                .session(webSession)
                .with(authentication(ownerAuth))
                .with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.link").value(org.hamcrest.Matchers.containsString("/join?token=")));
    }

    @Test
    void createInvitePersistsTokenToDatabase() throws Exception {
        mockMvc.perform(post("/settings/members/invite")
                .session(webSession)
                .with(authentication(ownerAuth))
                .with(csrf()))
            .andExpect(status().isCreated());

        List<WorkspaceInvite> invites = inviteRepository.findByWorkspaceIdOrderByCreatedAtDesc(testWorkspaceId);
        assertThat(invites).hasSize(1);
        assertThat(invites.get(0).getCreatedBy()).isEqualTo(owner.getId());
        assertThat(invites.get(0).getUsedAt()).isNull();
    }

    @Test
    void createInviteReturns401WithoutAuthentication() throws Exception {
        mockMvc.perform(post("/settings/members/invite")
                .session(webSession)
                .with(csrf()))
            .andExpect(status().is3xxRedirection());
    }

    // ── DELETE /settings/members/{id} ─────────────────────────────────────────

    @Test
    void removeMemberReturns204AndDeletesMembership() throws Exception {
        memberRepository.save(WorkspaceMember.builder()
            .workspaceId(testWorkspaceId)
            .appUserId(guest.getId())
            .role(WorkspaceRole.MEMBER.name())
            .build());

        mockMvc.perform(delete("/settings/members/" + guest.getId())
                .session(webSession)
                .with(authentication(ownerAuth))
                .with(csrf()))
            .andExpect(status().isNoContent());

        assertThat(memberRepository.existsByWorkspaceIdAndAppUserId(testWorkspaceId, guest.getId())).isFalse();
    }

    @Test
    void removeSelfReturns400() throws Exception {
        mockMvc.perform(delete("/settings/members/" + owner.getId())
                .session(webSession)
                .with(authentication(ownerAuth))
                .with(csrf()))
            .andExpect(status().isBadRequest());

        assertThat(memberRepository.existsByWorkspaceIdAndAppUserId(testWorkspaceId, owner.getId())).isTrue();
    }

    // ── GET /join ─────────────────────────────────────────────────────────────

    @Test
    void joinWithInvalidTokenShowsErrorPage() throws Exception {
        mockMvc.perform(get("/join").param("token", "invalid-token-xyz"))
            .andExpect(status().isOk())
            .andExpect(view().name("join-error"));
    }

    @Test
    void joinWithExpiredTokenShowsErrorPage() throws Exception {
        inviteRepository.save(WorkspaceInvite.builder()
            .workspaceId(testWorkspaceId)
            .token("expired-ctrl-token")
            .role(WorkspaceRole.MEMBER.name())
            .createdBy(owner.getId())
            .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .build());

        mockMvc.perform(get("/join").param("token", "expired-ctrl-token"))
            .andExpect(status().isOk())
            .andExpect(view().name("join-error"));
    }

    @Test
    void joinWithValidTokenUnauthenticatedRedirectsToLogin() throws Exception {
        WorkspaceInvite invite = inviteService.createInvite(testWorkspaceId, owner.getId());

        mockMvc.perform(get("/join").param("token", invite.getToken()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/oauth2/authorization/github"));
    }

    @Test
    void joinWithValidTokenAuthenticatedConsumesMembershipAndRedirects() throws Exception {
        WorkspaceInvite invite = inviteService.createInvite(testWorkspaceId, owner.getId());
        OAuth2AuthenticationToken guestAuth = oauthToken(guest);

        mockMvc.perform(get("/join")
                .param("token", invite.getToken())
                .with(authentication(guestAuth))
                .session(webSession))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/report"));

        assertThat(memberRepository.existsByWorkspaceIdAndAppUserId(testWorkspaceId, guest.getId())).isTrue();

        Optional<WorkspaceInvite> used = inviteRepository.findById(invite.getId());
        assertThat(used).isPresent();
        assertThat(used.get().getUsedAt()).isNotNull();
        assertThat(used.get().getUsedByAppUserId()).isEqualTo(guest.getId());
    }

    @Test
    void joinWithValidTokenAlreadyMemberStillRedirectsToReport() throws Exception {
        WorkspaceInvite invite = inviteService.createInvite(testWorkspaceId, owner.getId());

        // owner is already a member
        mockMvc.perform(get("/join")
                .param("token", invite.getToken())
                .with(authentication(ownerAuth))
                .session(webSession))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/report"));

        long ownerCount = memberRepository.findByWorkspaceId(testWorkspaceId).stream()
            .filter(m -> m.getAppUserId().equals(owner.getId()))
            .count();
        assertThat(ownerCount).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static OAuth2AuthenticationToken oauthToken(AppUser appUser) {
        DefaultOAuth2User oauthUser = new DefaultOAuth2User(
            List.of(),
            Map.of("login", appUser.getGithubLogin() != null
                ? appUser.getGithubLogin()
                : "unknown"),
            "login"
        );
        AppUserPrincipal principal = new AppUserPrincipal(appUser, oauthUser);
        return new OAuth2AuthenticationToken(principal, List.of(), "github");
    }
}
