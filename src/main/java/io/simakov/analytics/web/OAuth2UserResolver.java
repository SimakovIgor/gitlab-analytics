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
        return Map.of(
            "name", attrs.getOrDefault("name", username),
            "username", username != null
                ? username
                : "",
            "avatarUrl", attrs.getOrDefault("avatar_url", ""),
            "provider", provider
        );
    }
}
