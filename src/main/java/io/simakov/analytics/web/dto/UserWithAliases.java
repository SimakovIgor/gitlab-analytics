package io.simakov.analytics.web.dto;

import io.simakov.analytics.domain.model.TrackedUser;
import io.simakov.analytics.domain.model.TrackedUserAlias;

import java.util.List;

/**
 * Pairs a {@link TrackedUser} with its loaded aliases.
 * Replaces the raw {@code Map<String, Object>} used previously.
 */
public record UserWithAliases(TrackedUser user, List<TrackedUserAlias> aliases) {

}
