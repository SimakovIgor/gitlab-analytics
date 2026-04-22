package io.simakov.analytics.domain.repository;

import java.time.Instant;

public interface MttrIncidentProjection {

    String getWeekLabel();

    String getJiraKey();

    Instant getImpactStartedAt();

    Double getDurationHours();
}
