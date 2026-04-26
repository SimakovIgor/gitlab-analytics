package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.repository.AppUserRepository;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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

    /** Drain any requests left over from previous tests so takeRequest() returns the right one. */
    @BeforeEach
    void drainResendQueue() throws InterruptedException {
        RecordedRequest stale = RESEND_SERVER.takeRequest(0, TimeUnit.MILLISECONDS);
        while (stale != null) {
            stale = RESEND_SERVER.takeRequest(0, TimeUnit.MILLISECONDS);
        }
    }

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

        String savedToken = appUserRepository.findByEmail("emailtest@example.com")
            .map(AppUser::getEmailVerificationToken)
            .orElseThrow();

        RecordedRequest request = RESEND_SERVER.takeRequest(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-resend-key");
        assertThat(request.getBody().readUtf8()).contains(savedToken);
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

        Optional<AppUser> newUser = appUserRepository.findByEmail("expired@example.com");
        assertThat(newUser).isPresent();
        assertThat(newUser.get().getName()).isEqualTo("Fresh User");
        assertThat(newUser.get().getEmailVerificationToken()).isNotEqualTo("expired-token-xyz");
        assertThat(newUser.get().getEmailVerificationExpiresAt()).isAfter(Instant.now());
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
    }

    @Test
    void registerWhenResendFailsReturnsErrorAndDoesNotSaveUser() throws Exception {
        // Temporarily make Resend return 500 to simulate delivery failure
        Dispatcher errorDispatcher = new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(500).setBody("{\"error\":\"internal\"}");
            }
        };
        RESEND_SERVER.setDispatcher(errorDispatcher);
        try {
            mockMvc.perform(post("/register")
                    .param("name", "Resend Fail User")
                    .param("email", "resendfail@example.com")
                    .param("password", "password123")
                    .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attributeExists("error"));

            assertThat(appUserRepository.findByEmail("resendfail@example.com")).isEmpty();
        } finally {
            // Restore the default success dispatcher
            RESEND_SERVER.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody("{\"id\":\"test-email-id\"}");
                }
            });
        }
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
