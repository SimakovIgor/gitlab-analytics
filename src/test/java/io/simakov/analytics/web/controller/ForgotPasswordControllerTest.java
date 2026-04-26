package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.repository.AppUserRepository;
import io.simakov.analytics.domain.repository.PasswordResetTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class ForgotPasswordControllerTest extends BaseIT {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ── GET /forgot-password ─────────────────────────────────────────────────

    @Test
    void forgotPasswordPage_rendersWithoutError() throws Exception {
        // This GET was throwing 500 due to missing "sent" model attribute
        mockMvc.perform(get("/forgot-password"))
            .andExpect(status().isOk())
            .andExpect(view().name("forgot-password"))
            .andExpect(model().attribute("sent", false));
    }

    @Test
    void forgotPasswordPage_isAccessibleWithoutLogin() throws Exception {
        mockMvc.perform(get("/forgot-password"))
            .andExpect(status().isOk());
    }

    // ── POST /forgot-password ────────────────────────────────────────────────

    @Test
    void forgotPassword_setsSentTrueForKnownEmail() throws Exception {
        createVerifiedUser("reset@test.com", "pass");

        mockMvc.perform(post("/forgot-password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", "reset@test.com"))
            .andExpect(status().isOk())
            .andExpect(view().name("forgot-password"))
            .andExpect(model().attribute("sent", true));

        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    void forgotPassword_setsSentTrueForUnknownEmailToo() throws Exception {
        // Must not reveal whether email exists (prevent enumeration)
        mockMvc.perform(post("/forgot-password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", "nobody@example.com"))
            .andExpect(status().isOk())
            .andExpect(view().name("forgot-password"))
            .andExpect(model().attribute("sent", true));

        // No token created for unknown email
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    void forgotPassword_createsTokenInDb() throws Exception {
        createVerifiedUser("token-test@test.com", "pass");

        mockMvc.perform(post("/forgot-password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", "token-test@test.com"))
            .andExpect(status().isOk());

        assertThat(tokenRepository.findAll()).hasSize(1);
        assertThat(tokenRepository.findAll().get(0).getExpiresAt())
            .isAfter(Instant.now());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void createVerifiedUser(String email,
                                    String password) {
        appUserRepository.save(AppUser.builder()
            .email(email)
            .name("Test User")
            .passwordHash(passwordEncoder.encode(password))
            .emailVerified(true)
            .lastLoginAt(Instant.now())
            .build());
    }
}
