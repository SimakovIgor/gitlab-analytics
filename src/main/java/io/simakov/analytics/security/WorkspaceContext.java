package io.simakov.analytics.security;

/**
 * Thread-local holder for the current workspace ID.
 * Populated by BearerTokenAuthFilter (API) and WorkspaceAwareSuccessHandler (web).
 */
public final class WorkspaceContext {

    private static final ThreadLocal<Long> WORKSPACE_ID = new ThreadLocal<>();

    private WorkspaceContext() {
    }

    public static void set(Long workspaceId) {
        WORKSPACE_ID.set(workspaceId);
    }

    public static Long get() {
        Long id = WORKSPACE_ID.get();
        if (id == null) {
            throw new IllegalStateException("No workspace in current context");
        }
        return id;
    }

    public static void clear() {
        WORKSPACE_ID.remove();
    }
}
