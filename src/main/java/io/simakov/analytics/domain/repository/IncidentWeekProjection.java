package io.simakov.analytics.domain.repository;

public interface IncidentWeekProjection {

    String getWeekLabel();

    Long getIncidentCount();
}
