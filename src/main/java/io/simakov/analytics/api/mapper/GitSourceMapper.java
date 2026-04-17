package io.simakov.analytics.api.mapper;

import io.simakov.analytics.api.dto.response.GitSourceResponse;
import io.simakov.analytics.domain.model.GitSource;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GitSourceMapper {

    GitSourceResponse toResponse(GitSource source);
}
