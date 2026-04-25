package io.simakov.analytics.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.simakov.analytics.domain.model.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Unified principal wrapping an {@link AppUser} for email+password form login.
 */
@SuppressFBWarnings("SE_BAD_FIELD")
public class AppUserPrincipal implements UserDetails {

    private final AppUser appUser;
    private final Collection<? extends GrantedAuthority> authorities;

    public AppUserPrincipal(AppUser appUser) {
        this.appUser = appUser;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    public AppUser getAppUser() {
        return appUser;
    }

    @Override
    public String getUsername() {
        return appUser.getEmail();
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
