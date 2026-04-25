package io.simakov.analytics.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
@RequiredArgsConstructor
public class DevSecurityConfig {

    private final BearerTokenAuthFilter bearerTokenAuthFilter;
    private final WorkspaceAwareSuccessHandler workspaceAwareSuccessHandler;
    private final AppUserDetailsService appUserDetailsService;
    private final PasswordEncoder passwordEncoder;

    @Bean
    @Order(2)
    public SecurityFilterChain devWebFilterChain(HttpSecurity http) throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(appUserDetailsService);
        provider.setPasswordEncoder(passwordEncoder);

        return http
            .authenticationProvider(provider)
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**",
                    "/actuator/**",
                    "/login",
                    "/register",
                    "/join",
                    "/css/**",
                    "/js/**",
                    "/error"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(bearerTokenAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .successHandler(workspaceAwareSuccessHandler)
                .failureUrl("/login?error")
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
            )
            .build();
    }
}
