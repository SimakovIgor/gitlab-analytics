package io.simakov.analytics.api.dto.dora;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.simakov.analytics.dora.model.DeployStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

/**
 * Manual API request to record a deployment event.
 *
 * <p>Minimal curl example:
 * <pre>
 * curl -X POST /api/dora/events/deploy \
 *   -H "Authorization: Bearer $TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{
 *     "idempotencyKey": "deploy-prod-v1.2.3",
 *     "service": "payments-service",
 *     "environment": "production",
 *     "deployedAt": "2026-04-27T14:00:00Z",
 *     "status": "SUCCESS"
 *   }'
 * </pre>
 */
public record DeployEventRequest(

    /** Client-supplied deduplication key. Safe to retry — identical key returns existing event. */
    @NotBlank
    String idempotencyKey,

    /** Service name as configured in GitPulse (or will be auto-created if not found). */
    @NotBlank
    String service,

    /** Target environment. Use a stable name, e.g. "production", "staging". */
    @NotBlank
    String environment,

    @NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant deployedAt,

    @NotNull
    DeployStatus status,

    /** Release tag name or version string (e.g. "v1.2.3"). */
    String version,

    /** HEAD commit SHA of this deployment. */
    String commitSha,

    /**
     * Start of commit range deployed (exclusive).
     * Together with commitRangeTo enables Lead Time for Changes calculation.
     */
    String commitRangeFrom,

    /** End of commit range deployed (inclusive, usually equals commitSha). */
    String commitRangeTo,

    /** URL to the deployment in the source system (CI run, pipeline, etc.). */
    String externalUrl,

    /** Free-form metadata stored as JSON. Not used in metric calculations. */
    Map<String, Object> metadata
) {

}
