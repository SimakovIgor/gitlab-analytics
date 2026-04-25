package io.simakov.analytics.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Dev-profile override that disables CSRF for the web chain.
 * CSRF protection is not needed in local development — it only causes
 * session-setup friction when using DevAutoLoginFilter.
 *
 * <p>This config replaces the default {@code webFilterChain} from
 * {@link io.simakov.analytics.config.SecurityConfig} for the "dev" profile.
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    private final AppUserOauthService appUserOauthService;
    private final BearerTokenAuthFilter bearerTokenAuthFilter;
    private final WorkspaceAwareSuccessHandler workspaceAwareSuccessHandler;

    public DevSecurityConfig(AppUserOauthService appUserOauthService,
                             BearerTokenAuthFilter bearerTokenAuthFilter,
                             WorkspaceAwareSuccessHandler workspaceAwareSuccessHandler) {
        this.appUserOauthService = appUserOauthService;
        this.bearerTokenAuthFilter = bearerTokenAuthFilter;
        this.workspaceAwareSuccessHandler = workspaceAwareSuccessHandler;
    }

    /**
     * Web UI filter chain with CSRF disabled for local dev convenience.
     * Order(2) matches the production webFilterChain so beans are interchangeable.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain devWebFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**",
                    "/actuator/**",
                    "/login",
                    "/join",
                    "/css/**",
                    "/js/**",
                    "/error"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(bearerTokenAuthFilter,
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .userInfoEndpoint(ui -> ui.userService(appUserOauthService))
                .successHandler(workspaceAwareSuccessHandler)
                .failureUrl("/login?error")
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
            )
            .build();
    }
}
