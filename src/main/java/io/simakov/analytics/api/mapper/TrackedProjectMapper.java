package io.simakov.analytics.api.mapper;

import io.simakov.analytics.api.dto.request.CreateTrackedProjectRequest;
import io.simakov.analytics.api.dto.response.TrackedProjectResponse;
import io.simakov.analytics.domain.model.TrackedProject;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TrackedProjectMapper {

    @Mapping(target = "id",
             ignore = true)
    @Mapping(target = "createdAt",
             ignore = true)
    @Mapping(target = "updatedAt",
             ignore = true)
    @Mapping(target = "enabled",
             expression = "java(true)")
    TrackedProject toEntity(CreateTrackedProjectRequest request);

    TrackedProjectResponse toResponse(TrackedProject project);
}
