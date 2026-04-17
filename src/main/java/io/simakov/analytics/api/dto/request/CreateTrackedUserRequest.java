package io.simakov.analytics.api.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateTrackedUserRequest(
    @NotBlank String displayName,
    @Email String email,
    /** Additional emails to register as aliases (e.g. GitHub noreply addresses). */
    List<String> aliasEmails
) {

}
