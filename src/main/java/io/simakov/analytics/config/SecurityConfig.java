package io.simakov.analytics.config;

import io.simakov.analytics.security.BearerTokenAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final BearerTokenAuthFilter bearerTokenAuthFilter;

    /**
     * Filter chain for REST API endpoints (/api/**).
     * Stateless, Bearer token authentication.
     */
    @Bean
    @Order(1)
    @SuppressWarnings("checkstyle:IllegalCatch")
    public SecurityFilterChain apiFilterChain(HttpSecurity http) {
        try {
            return http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .addFilterBefore(bearerTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Filter chain for web UI routes.
     * Session-based, OAuth2 login via GitLab.
     */
    @Bean
    @Order(2)
    @SuppressWarnings("checkstyle:IllegalCatch")
    public SecurityFilterChain webFilterChain(HttpSecurity http) {
        try {
            return http
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers(
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/actuator/**",
                        "/login",
                        "/css/**",
                        "/js/**",
                        "/error"
                    ).permitAll()
                    .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> oauth2
                    .loginPage("/login")
                    .defaultSuccessUrl("/dashboard", true)
                    .failureUrl("/login?error")
                )
                .logout(logout -> logout
                    .logoutSuccessUrl("/login?logout")
                )
                .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
