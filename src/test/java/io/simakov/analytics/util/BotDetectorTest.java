package io.simakov.analytics.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class BotDetectorTest {

    @ParameterizedTest
    @CsvSource({
        "Placeholder github actions, github-actions, true",
        "Placeholder Nikita Ivanov, nikita.ivanov, true",
        "Placeholder Simakov, simakov, true",
        "dependabot, dependabot[bot], true",
        "Renovate Bot, renovate[bot], true",
        "project_123_bot, project_123_bot, true",
        "group_456_bot, group_456_bot, true",
        "Service Account, service-account-ci, true",
        "Ghost User, ghost, true",
        "Administrator, root, true",
        "GitLab Admin, admin, true",
        "Igor Simakov, isimakov, false",
        "Aleksei Upatov, a.upatov, false",
        "Denis Sermyagin, d.sermyagin, false"
    })
    void isSuspectedBot(String name, String username, boolean expected) {
        assertThat(BotDetector.isSuspectedBot(name, username)).isEqualTo(expected);
    }

    @Test
    void nullNameAndUsername() {
        assertThat(BotDetector.isSuspectedBot(null, null)).isFalse();
    }

    @Test
    void noreplyEmail() {
        assertThat(BotDetector.isSuspectedBot("CI Bot", "cibot", "noreply@gitlab.com")).isTrue();
        assertThat(BotDetector.isSuspectedBot("User", "user", "user@company.com")).isFalse();
    }

    @Test
    void mergeRequestTokenEmail() {
        assertThat(BotDetector.isSuspectedBot("User", "user",
            "project_123_bot_abc+merge-request@noreply.gitlab.com")).isTrue();
    }
}
