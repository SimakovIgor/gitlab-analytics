package io.simakov.analytics.security;

import io.simakov.analytics.domain.model.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Unified principal wrapping an {@link AppUser}. Implements both {@link OAuth2User}
 * (for GitHub login) and {@link UserDetails} (for email+password form login).
 *
 * <p>When constructed without an OAuth2User delegate (email+password path) the
 * attributes map is empty and getPassword() returns the stored BCrypt hash.
 */
public class AppUserPrincipal implements OAuth2User, UserDetails {

    private final AppUser appUser;
    private final OAuth2User delegate;
    private final Collection<? extends GrantedAuthority> authorities;

    /** OAuth2 constructor — used by {@link AppUserOauthService}. */
    public AppUserPrincipal(AppUser appUser, OAuth2User delegate) {
        this.appUser = appUser;
        this.delegate = delegate;
        this.authorities = delegate.getAuthorities();
    }

    /** Form-login constructor — used by {@link AppUserDetailsService}. */
    public AppUserPrincipal(AppUser appUser) {
        this.appUser = appUser;
        this.delegate = null;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    public AppUser getAppUser() {
        return appUser;
    }

    // ── OAuth2User ────────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> getAttributes() {
        return delegate != null ? delegate.getAttributes() : Map.of();
    }

    /** OAuth2User#getName — returns GitHub login or email for password users. */
    @Override
    public String getName() {
        if (delegate != null) {
            return appUser.getGithubLogin();
        }
        return appUser.getEmail() != null ? appUser.getEmail() : String.valueOf(appUser.getId());
    }

    // ── UserDetails ───────────────────────────────────────────────────────────

    /** Primary identifier used by Spring Security for form-login. */
    @Override
    public String getUsername() {
        return appUser.getEmail() != null ? appUser.getEmail() : appUser.getGithubLogin();
    }

    @Override
    public String getPassword() {
        return appUser.getPasswordHash();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
