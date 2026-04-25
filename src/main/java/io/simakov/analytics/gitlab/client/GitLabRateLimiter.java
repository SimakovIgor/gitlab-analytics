package io.simakov.analytics.gitlab.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Simple per-host token-bucket rate limiter for GitLab API calls.
 * Only applied to {@code gitlab.com} (the public cloud, which enforces 600 req/min).
 * Self-hosted instances are not throttled here — rely on retry-with-backoff instead.
 *
 * <p>Configuration: 10 tokens/second burst, refill thread running every 100 ms.
 * Acquiring a permit blocks at most 10 seconds before throwing.
 */
@Slf4j
@Component
public class GitLabRateLimiter {

    private static final String GITLAB_COM_HOST = "gitlab.com";
    /** Max requests per second for gitlab.com (600/min → 10/sec). */
    private static final int TOKENS_PER_SECOND = 10;
    private static final int REFILL_INTERVAL_MS = 100;
    private static final long ACQUIRE_TIMEOUT_SECONDS = 10L;

    private final ConcurrentMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    /**
     * Acquires a rate-limit permit for the given base URL.
     * No-op for self-hosted instances.
     *
     * @throws IllegalStateException if a permit cannot be acquired within the timeout
     */
    public void acquire(String baseUrl) {
        if (!isRateLimited(baseUrl)) {
            return;
        }
        Semaphore semaphore = semaphores.computeIfAbsent(GITLAB_COM_HOST, k -> {
            Semaphore s = new Semaphore(TOKENS_PER_SECOND, true);
            scheduleRefill(s);
            return s;
        });
        try {
            if (!semaphore.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Rate limit acquire timed out for {}", baseUrl);
                throw new IllegalStateException("GitLab.com rate limit: too many pending requests");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for rate limit permit", e);
        }
    }

    private boolean isRateLimited(String baseUrl) {
        return baseUrl != null && baseUrl.contains(GITLAB_COM_HOST);
    }

    private void scheduleRefill(Semaphore semaphore) {
        Thread refillThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(REFILL_INTERVAL_MS);
                    int deficit = TOKENS_PER_SECOND - semaphore.availablePermits();
                    if (deficit > 0) {
                        semaphore.release(deficit);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "gitlab-rate-limiter-refill");
        refillThread.setDaemon(true);
        refillThread.start();
    }
}
