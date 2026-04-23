package io.simakov.analytics.web.dto;

/**
 * Pre-computed MR row for the dev profile page.
 * All formatting done in Java to avoid complex Thymeleaf SpEL expressions.
 */
public record DevProfileMrRow(
        String title,
        String webUrl,
        String openedAt,
        String mergedAt,
        String timeToMerge,
        String timeColor,
        String additions,
        String deletions
) {
}
