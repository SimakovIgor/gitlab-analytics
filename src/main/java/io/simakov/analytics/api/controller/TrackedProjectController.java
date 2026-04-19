package io.simakov.analytics.api.controller;

import io.simakov.analytics.api.dto.request.CreateTrackedProjectRequest;
import io.simakov.analytics.api.dto.request.EnableProjectRequest;
import io.simakov.analytics.api.dto.response.TrackedProjectResponse;
import io.simakov.analytics.api.exception.ResourceNotFoundException;
import io.simakov.analytics.api.mapper.TrackedProjectMapper;
import io.simakov.analytics.domain.model.TrackedProject;
import io.simakov.analytics.domain.repository.GitSourceRepository;
import io.simakov.analytics.domain.repository.TrackedProjectRepository;
import io.simakov.analytics.encryption.EncryptionService;
import io.simakov.analytics.security.WorkspaceContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Tag(name = "Tracked Projects",
     description = "Manage repositories to track")
public class TrackedProjectController {

    private final TrackedProjectRepository trackedProjectRepository;
    private final GitSourceRepository gitSourceRepository;
    private final TrackedProjectMapper trackedProjectMapper;
    private final EncryptionService encryptionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a project for tracking")
    public TrackedProjectResponse create(@RequestBody @Valid CreateTrackedProjectRequest request) {
        Long workspaceId = WorkspaceContext.get();
        gitSourceRepository.findById(request.gitSourceId())
            .filter(s -> workspaceId.equals(s.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("GitSource", request.gitSourceId()));
        TrackedProject project = trackedProjectMapper.toEntity(request);
        project.setWorkspaceId(workspaceId);
        project.setTokenEncrypted(encryptionService.encrypt(request.token()));
        return trackedProjectMapper.toResponse(trackedProjectRepository.save(project));
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "List all tracked projects")
    public List<TrackedProjectResponse> list() {
        Long workspaceId = WorkspaceContext.get();
        return trackedProjectRepository.findAllByWorkspaceId(workspaceId).stream()
            .map(trackedProjectMapper::toResponse)
            .toList();
    }

    @PatchMapping("/{id}/enable")
    @Operation(summary = "Enable or disable a tracked project")
    public TrackedProjectResponse enable(@PathVariable Long id,
                                         @RequestBody @Valid EnableProjectRequest request) {
        Long workspaceId = WorkspaceContext.get();
        TrackedProject project = trackedProjectRepository.findById(id)
            .filter(p -> workspaceId.equals(p.getWorkspaceId()))
            .orElseThrow(() -> new ResourceNotFoundException("TrackedProject", id));
        project.setEnabled(request.enabled());
        return trackedProjectMapper.toResponse(trackedProjectRepository.save(project));
    }
}
