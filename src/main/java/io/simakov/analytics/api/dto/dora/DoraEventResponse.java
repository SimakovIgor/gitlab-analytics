package io.simakov.analytics.api.dto.dora;

/**
 * Response returned by Manual DORA Events API.
 */
public record DoraEventResponse(
    Long id,
    String status,
    boolean serviceResolved,
    String warning
) {

    public static DoraEventResponse created(Long id, boolean serviceResolved) {
        return new DoraEventResponse(id, "CREATED", serviceResolved, null);
    }

    public static DoraEventResponse created(Long id, boolean serviceResolved, String warning) {
        return new DoraEventResponse(id, "CREATED", serviceResolved, warning);
    }

    public static DoraEventResponse duplicate(Long id, boolean serviceResolved) {
        return new DoraEventResponse(id, "DUPLICATE", serviceResolved, null);
    }
}
