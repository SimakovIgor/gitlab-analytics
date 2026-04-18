package io.simakov.analytics.web.dto;

import io.simakov.analytics.domain.model.TrackedProject;

public record CreatedProjectResult(
    TrackedProject project,
    long jobId
) {

}
