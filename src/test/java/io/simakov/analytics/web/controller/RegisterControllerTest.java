package io.simakov.analytics.web.controller;

import io.simakov.analytics.BaseIT;
import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

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

@AutoConfigureMockMvc
class RegisterControllerTest extends BaseIT {

    @Autowired
    private MockMvc mockMvc;

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
    void registerWithValidDataCreatesUserAndRedirectsToOnboarding() throws Exception {
        mockMvc.perform(post("/register")
                .param("name", "Test User")
                .param("email", "newuser@example.com")
                .param("password", "securepass123")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/onboarding"));

        Optional<AppUser> created = appUserRepository.findByEmail("newuser@example.com");
        assertThat(created).isPresent();
        assertThat(created.get().getName()).isEqualTo("Test User");
        assertThat(created.get().getPasswordHash()).startsWith("$2a$");
        assertThat(created.get().getGithubId()).isNull();
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
    void registerWithDuplicateEmailReturnsErrorOnRegisterPage() throws Exception {
        appUserRepository.save(AppUser.builder()
            .name("Existing User")
            .email("duplicate@example.com")
            .passwordHash(passwordEncoder.encode("somepass"))
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
    void loginWithValidCredentialsRedirectsToOnboarding() throws Exception {
        String email = "login-test@example.com";
        String password = "loginpass123";
        appUserRepository.save(AppUser.builder()
            .name("Login Test")
            .email(email)
            .passwordHash(passwordEncoder.encode(password))
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
