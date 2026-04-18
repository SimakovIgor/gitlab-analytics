package io.simakov.analytics.web;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
public class OAuth2UserResolver {

    public Map<String, Object> resolve(OAuth2AuthenticationToken authentication) {
        Map<String, Object> attrs = authentication.getPrincipal().getAttributes();
        String provider = authentication.getAuthorizedClientRegistrationId();
        String username = "github".equals(provider)
            ? (String) attrs.get("login")
            : (String) attrs.get("username");
        String resolvedUsername = username != null ? username : "";
        String name = (String) attrs.get("name");
        String avatarUrl = (String) attrs.get("avatar_url");
        return Map.of(
            "name", name != null ? name : resolvedUsername,
            "username", resolvedUsername,
            "avatarUrl", avatarUrl != null ? avatarUrl : "",
            "provider", provider
        );
    }
}
