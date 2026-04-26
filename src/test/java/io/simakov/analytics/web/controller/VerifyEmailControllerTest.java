package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class VerifyEmailControllerTest extends BaseIT {

    @Autowired
    private AppUserRepository appUserRepository;

    @Test
    void getVerifyEmailSentReturnsPage() throws Exception {
        mockMvc.perform(get("/verify-email-sent"))
            .andExpect(status().isOk())
            .andExpect(view().name("verify-email-sent"));
    }

    @Test
    void validTokenSetsEmailVerifiedAndRedirectsToLogin() throws Exception {
        AppUser user = appUserRepository.save(AppUser.builder()
            .name("Verify Me")
            .email("verify@example.com")
            .passwordHash("$2a$10$someHash")
            .emailVerified(false)
            .emailVerificationToken("validtoken123")
            .emailVerificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
            .lastLoginAt(Instant.now())
            .build());

        mockMvc.perform(get("/verify-email").param("token", "validtoken123"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?verified"));

        Optional<AppUser> updated = appUserRepository.findById(user.getId());
        assertThat(updated).isPresent();
        assertThat(updated.get().isEmailVerified()).isTrue();
        assertThat(updated.get().getEmailVerificationToken()).isNull();
        assertThat(updated.get().getEmailVerificationExpiresAt()).isNull();
    }

    @Test
    void invalidTokenRedirectsToLoginWithInvalidToken() throws Exception {
        mockMvc.perform(get("/verify-email").param("token", "nonexistenttoken"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?invalidToken"));
    }

    @Test
    void expiredTokenRedirectsToLoginWithTokenExpired() throws Exception {
        appUserRepository.save(AppUser.builder()
            .name("Expired User")
            .email("expired@example.com")
            .passwordHash("$2a$10$someHash")
            .emailVerified(false)
            .emailVerificationToken("expiredtoken456")
            .emailVerificationExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .lastLoginAt(Instant.now())
            .build());

        mockMvc.perform(get("/verify-email").param("token", "expiredtoken456"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?tokenExpired"));
    }

    @Test
    void validTokenWithInviteParamPreservesInviteInSession() throws Exception {
        appUserRepository.save(AppUser.builder()
            .name("Invite User")
            .email("invite@example.com")
            .passwordHash("$2a$10$someHash")
            .emailVerified(false)
            .emailVerificationToken("tokenwithinvite")
            .emailVerificationExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
            .lastLoginAt(Instant.now())
            .build());

        mockMvc.perform(get("/verify-email")
                .param("token", "tokenwithinvite")
                .param("invite", "inviteabc123"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?verified"));
    }
}
