package io.simakov.analytics.config;

import io.simakov.analytics.security.BearerTokenAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final BearerTokenAuthFilter bearerTokenAuthFilter;

    @Bean
    @SuppressWarnings("IllegalCatch")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        try {
            return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                    // Allow Swagger UI and actuator without auth
                    .requestMatchers(
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/actuator/**"
                    ).permitAll()
                    .anyRequest().authenticated()
                )
                .addFilterBefore(bearerTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
