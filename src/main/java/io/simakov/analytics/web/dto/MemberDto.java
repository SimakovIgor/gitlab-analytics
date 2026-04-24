package io.simakov.analytics.web.dto;

public record MemberDto(
    Long appUserId,
    String name,
    String username,
    String avatarUrl,
    String role,
    String joinedAt,
    boolean isOwner
) {
}
