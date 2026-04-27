package io.simakov.analytics.api.controller;

import io.simakov.analytics.api.dto.dora.DeployEventRequest;
import io.simakov.analytics.api.dto.dora.DoraEventResponse;
import io.simakov.analytics.api.dto.dora.IncidentEventRequest;
import io.simakov.analytics.dora.DoraEventService;
import io.simakov.analytics.security.WorkspaceContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dora/events")
@RequiredArgsConstructor
@Tag(name = "DORA Events", description = "Manual API for recording normalized deployment and incident events")
public class DoraEventsController {

    private final DoraEventService doraEventService;

    /**
     * Records a deployment event.
     * Idempotent — duplicate {@code idempotencyKey} returns 200 with status=DUPLICATE.
     * Integrates with Jenkins, GitHub Actions, GitLab CI, bash scripts, or any CI/CD tool.
     */
    @PostMapping("/deploy")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Record a deployment event",
               description = "Idempotent. Duplicate idempotencyKey returns the existing event with status=DUPLICATE.")
    public DoraEventResponse recordDeploy(@Valid @RequestBody DeployEventRequest request) {
        return doraEventService.recordDeploy(WorkspaceContext.get(), request);
    }

    /**
     * Records an incident event.
     * Idempotent — duplicate {@code idempotencyKey} returns 200 with status=DUPLICATE.
     * Integrates with Jira webhooks, PagerDuty, Sentry alerts, or any incident management tool.
     */
    @PostMapping("/incident")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Record an incident event",
               description = "Idempotent. Incidents without resolvedAt are excluded from MTTR calculation.")
    public DoraEventResponse recordIncident(@Valid @RequestBody IncidentEventRequest request) {
        return doraEventService.recordIncident(WorkspaceContext.get(), request);
    }
}
