package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.repository.AppUserRepository;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class RegisterControllerTest extends BaseIT {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void getRegisterReturnsRegistrationPage() throws Exception {
        mockMvc.perform(get("/register"))
            .andExpect(status().isOk())
            .andExpect(view().name("register"));
    }

    @Test
    void registerWithValidDataSavesUnverifiedUserAndRedirectsToVerifyEmailSent() throws Exception {
        mockMvc.perform(post("/register")
                .param("name", "Test User")
                .param("email", "newuser@example.com")
                .param("password", "securepass123")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/verify-email-sent"));

        Optional<AppUser> created = appUserRepository.findByEmail("newuser@example.com");
        assertThat(created).isPresent();
        assertThat(created.get().getName()).isEqualTo("Test User");
        assertThat(created.get().getPasswordHash()).startsWith("$2a$");
        assertThat(created.get().isEmailVerified()).isFalse();
        assertThat(created.get().getEmailVerificationToken()).isNotBlank();
        assertThat(created.get().getGithubId()).isNull();
    }

    @Test
    void registerSendsVerificationEmailWithTokenLink() throws Exception {
        mockMvc.perform(post("/register")
                .param("name", "Email User")
                .param("email", "emailtest@example.com")
                .param("password", "password123")
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        greenMail.waitForIncomingEmail(3000, 1);

        String savedToken = appUserRepository.findByEmail("emailtest@example.com")
            .map(AppUser::getEmailVerificationToken)
            .orElseThrow();

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("emailtest@example.com");
        assertThat(received[0].getContent().toString()).contains(savedToken);
    }

    @Test
    void registerNormalizesEmailToLowerCase() throws Exception {
        mockMvc.perform(post("/register")
                .param("name", "Case Test")
                .param("email", "UPPER@EXAMPLE.COM")
                .param("password", "password123")
                .with(csrf()))
            .andExpect(status().is3xxRedirection());

        assertThat(appUserRepository.findByEmail("upper@example.com")).isPresent();
        assertThat(appUserRepository.findByEmail("UPPER@EXAMPLE.COM")).isEmpty();
    }

    @Test
    void registerWhenSmtpFailsReturnsErrorAndDoesNotSaveUser() throws Exception {
        greenMail.stop();
        try {
            mockMvc.perform(post("/register")
                    .param("name", "SMTP Fail User")
                    .param("email", "smtpfail@example.com")
                    .param("password", "password123")
                    .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attributeExists("error"));

            assertThat(appUserRepository.findByEmail("smtpfail@example.com")).isEmpty();
        } finally {
            greenMail.start();
        }
    }

    @Test
    void registerWithDuplicateEmailReturnsErrorOnRegisterPage() throws Exception {
        appUserRepository.save(AppUser.builder()
            .name("Existing User")
            .email("duplicate@example.com")
            .passwordHash(passwordEncoder.encode("somepass"))
            .emailVerified(true)
            .lastLoginAt(Instant.now())
            .build());

        mockMvc.perform(post("/register")
                .param("name", "New User")
                .param("email", "duplicate@example.com")
                .param("password", "anotherpass")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("register"))
            .andExpect(model().attributeExists("error"));

        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void registerWithUnverifiedEmailAndValidToken_showsCheckEmailMessage() throws Exception {
        appUserRepository.save(AppUser.builder()
            .name("Pending User")
            .email("pending@example.com")
            .passwordHash(passwordEncoder.encode("somepass"))
            .emailVerified(false)
            .emailVerificationToken("valid-token-abc")
            .emailVerificationExpiresAt(Instant.now().plusSeconds(3600))
            .lastLoginAt(Instant.now())
            .build());

        mockMvc.perform(post("/register")
                .param("name", "Pending User")
                .param("email", "pending@example.com")
                .param("password", "anotherpass")
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(view().name("register"))
            .andExpect(model().attributeExists("error"));

        // Existing unverified user must not be deleted or duplicated
        assertThat(appUserRepository.findByEmail("pending@example.com")).isPresent();
        assertThat(appUserRepository.findAll().stream()
            .filter(u -> "pending@example.com".equals(u.getEmail()))
            .count()).isEqualTo(1);
    }

    @Test
    void registerWithUnverifiedEmailAndExpiredToken_deletesStaleUserAndCreatesNew() throws Exception {
        appUserRepository.save(AppUser.builder()
            .name("Expired User")
            .email("expired@example.com")
            .passwordHash(passwordEncoder.encode("oldpass"))
            .emailVerified(false)
            .emailVerificationToken("expired-token-xyz")
            .emailVerificationExpiresAt(Instant.now().minusSeconds(1))
            .lastLoginAt(Instant.now())
            .build());

        mockMvc.perform(post("/register")
                .param("name", "Fresh User")
                .param("email", "expired@example.com")
                .param("password", "newpass123")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/verify-email-sent"));

        // Stale record replaced by new one with fresh token
        Optional<AppUser> newUser = appUserRepository.findByEmail("expired@example.com");
        assertThat(newUser).isPresent();
        assertThat(newUser.get().getName()).isEqualTo("Fresh User");
        assertThat(newUser.get().getEmailVerificationToken()).isNotEqualTo("expired-token-xyz");
        assertThat(newUser.get().getEmailVerificationExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void loginWithValidCredentialsRedirectsToOnboarding() throws Exception {
        String email = "login-test@example.com";
        String password = "loginpass123";
        appUserRepository.save(AppUser.builder()
            .name("Login Test")
            .email(email)
            .passwordHash(passwordEncoder.encode(password))
            .emailVerified(true)
            .lastLoginAt(Instant.now())
            .build());

        mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", password)
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/onboarding"));
    }

    @Test
    void loginWithWrongPasswordRedirectsToLoginError() throws Exception {
        String email = "wrongpass@example.com";
        appUserRepository.save(AppUser.builder()
            .name("Wrong Pass")
            .email(email)
            .passwordHash(passwordEncoder.encode("correctpassword"))
            .emailVerified(true)
            .lastLoginAt(Instant.now())
            .build());

        mockMvc.perform(post("/login")
                .param("email", email)
                .param("password", "wrongpassword")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void loginWithUnknownEmailRedirectsToLoginError() throws Exception {
        mockMvc.perform(post("/login")
                .param("email", "nobody@example.com")
                .param("password", "anypassword")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?error"));
    }
}
