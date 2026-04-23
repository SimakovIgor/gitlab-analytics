package io.simakov.analytics.util;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Heuristic filter for bot / placeholder / system GitLab accounts.
 * Used during auto-discovery and onboarding to skip non-human authors.
 */
public final class BotDetector {

    private static final Set<String> SYSTEM_USERNAMES = Set.of(
        "ghost", "root", "admin", "gitlab-bot", "gitlab"
    );

    private static final Pattern BOT_USERNAME_PATTERN = Pattern.compile(
        "^(project_|group_|service-account-)"
            + "|\\[bot]$"
    );

    private static final Pattern BOT_NAME_PATTERN = Pattern.compile(
        "^placeholder\\b", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NOREPLY_EMAIL_PATTERN = Pattern.compile(
        "noreply|no-reply|\\+merge-request@"
    );

    private BotDetector() {
    }

    /**
     * Returns {@code true} if the author looks like a bot, placeholder, or system account.
     *
     * @param name     display name from GitLab (nullable)
     * @param username GitLab username (nullable)
     */
    public static boolean isSuspectedBot(String name, String username) {
        if (username != null) {
            String lower = username.toLowerCase(Locale.ROOT);
            if (SYSTEM_USERNAMES.contains(lower) || BOT_USERNAME_PATTERN.matcher(lower).find()) {
                return true;
            }
        }
        return name != null && BOT_NAME_PATTERN.matcher(name).find();
    }

    /**
     * Overload that also checks email patterns (noreply, merge-request tokens).
     */
    public static boolean isSuspectedBot(String name, String username, String email) {
        return isSuspectedBot(name, username)
            || email != null && NOREPLY_EMAIL_PATTERN.matcher(email.toLowerCase(Locale.ROOT)).find();
    }
}
