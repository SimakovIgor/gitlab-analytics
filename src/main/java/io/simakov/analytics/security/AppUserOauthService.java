package io.simakov.analytics.security;

import io.simakov.analytics.domain.model.AppUser;
import io.simakov.analytics.domain.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppUserOauthService extends DefaultOAuth2UserService {

    private final AppUserRepository appUserRepository;

    @Override
    @Transactional
    @SuppressWarnings("PMD.AvoidUncheckedExceptionsInSignatures")
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        Number idAttribute = oAuth2User.getAttribute("id");
        if (idAttribute == null) {
            throw new OAuth2AuthenticationException("GitHub OAuth2 response missing 'id' attribute");
        }
        Long githubId = idAttribute.longValue();
        String login = oAuth2User.getAttribute("login");
        String name = oAuth2User.getAttribute("name");
        String avatarUrl = oAuth2User.getAttribute("avatar_url");
        String email = oAuth2User.getAttribute("email");

        Optional<AppUser> existing = appUserRepository.findByGithubId(githubId);
        AppUser appUser;
        if (existing.isPresent()) {
            appUser = existing.get();
            appUser.setGithubLogin(login);
            appUser.setName(name);
            appUser.setAvatarUrl(avatarUrl);
            appUser.setEmail(email);
            appUser.setLastLoginAt(Instant.now());
            appUser = appUserRepository.save(appUser);
            log.debug("Updated AppUser id={} login={}", appUser.getId(), login);
        } else {
            appUser = appUserRepository.save(AppUser.builder()
                .githubId(githubId)
                .githubLogin(login)
                .name(name)
                .avatarUrl(avatarUrl)
                .email(email)
                .lastLoginAt(Instant.now())
                .build());
            log.info("Created new AppUser id={} login={}", appUser.getId(), login);
        }

        return new AppUserPrincipal(appUser, oAuth2User);
    }
}
