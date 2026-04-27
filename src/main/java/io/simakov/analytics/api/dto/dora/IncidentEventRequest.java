package io.simakov.analytics.api.dto.dora;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

/**
 * Manual API request to record an incident event.
 *
 * <p>Minimal curl example:
 * <pre>
 * curl -X POST /api/dora/events/incident \
 *   -H "Authorization: Bearer $TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{
 *     "idempotencyKey": "jira-INC-1234",
 *     "service": "payments-service",
 *     "startedAt": "2026-04-27T10:00:00Z",
 *     "resolvedAt": "2026-04-27T12:30:00Z"
 *   }'
 * </pre>
 */
public record IncidentEventRequest(

    /** Client-supplied deduplication key. Safe to retry — identical key returns existing event. */
    @NotBlank
    String idempotencyKey,

    /** Service name as configured in GitPulse (or will be auto-created if not found). */
    @NotBlank
    String service,

    @NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant startedAt,

    /** Null for open/unresolved incidents. Incidents without resolvedAt are excluded from MTTR. */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant resolvedAt,

    /** Severity label (P1/P2/SEV1/SEV2/etc.). Stored as-is, not normalized. */
    String severity,

    /** Status in the source system (OPEN/RESOLVED/IN_PROGRESS/etc.). */
    String status,

    /** External identifier in the source system (e.g. Jira key "INC-1234"). */
    String externalId,

    /** URL to the incident in the source system. */
    String externalUrl,

    /**
     * Version of the deployment that caused this incident.
     * When provided, linked to a DoraDeployEvent matching this version for precise CFR.
     */
    String causedByDeployVersion,

    /** Free-form metadata stored as JSON. Not used in metric calculations. */
    Map<String, Object> metadata
) {

}
