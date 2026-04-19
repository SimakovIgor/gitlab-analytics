package io.simakov.analytics.security;

import io.simakov.analytics.domain.model.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;

/**
 * Wraps OAuth2User and exposes the persisted AppUser entity.
 */
public class AppUserPrincipal implements OAuth2User {

    private final AppUser appUser;
    private final OAuth2User delegate;

    public AppUserPrincipal(AppUser appUser,
                            OAuth2User delegate) {
        this.appUser = appUser;
        this.delegate = delegate;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return delegate.getAuthorities();
    }

    @Override
    public String getName() {
        return appUser.getGithubLogin();
    }
}
