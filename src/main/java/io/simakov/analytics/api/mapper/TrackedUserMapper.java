package io.simakov.analytics.api.mapper;

import io.simakov.analytics.api.dto.request.AddUserAliasRequest;
import io.simakov.analytics.api.dto.request.CreateTrackedUserRequest;
import io.simakov.analytics.api.dto.request.UpdateTrackedUserRequest;
import io.simakov.analytics.api.dto.response.TrackedUserResponse;
import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TrackedUserMapper {

    @Mapping(target = "workspaceId",
             ignore = true)
    @Mapping(target = "id",
             ignore = true)
    @Mapping(target = "createdAt",
             ignore = true)
    @Mapping(target = "updatedAt",
             ignore = true)
    @Mapping(target = "enabled",
             expression = "java(true)")
    TrackedUser toEntity(CreateTrackedUserRequest request);

    @Mapping(target = "workspaceId",
             ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id",
             ignore = true)
    @Mapping(target = "createdAt",
             ignore = true)
    @Mapping(target = "updatedAt",
             ignore = true)
    void updateEntity(UpdateTrackedUserRequest request,
                      @MappingTarget TrackedUser user);

    @Mapping(target = "aliases",
             source = "aliases")
    TrackedUserResponse toResponse(TrackedUser user,
                                   List<TrackedUserAlias> aliases);

    TrackedUserResponse.AliasResponse toAliasResponse(TrackedUserAlias alias);

    @Mapping(target = "trackedUserId",
             source = "user.id")
    @Mapping(target = "email",
             source = "request.email")
    @Mapping(target = "id",
             ignore = true)
    @Mapping(target = "createdAt",
             ignore = true)
    TrackedUserAlias toAlias(AddUserAliasRequest request,
                             TrackedUser user);
}
