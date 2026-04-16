package io.simakov.analytics.api.exception;

import org.springframework.http.HttpStatusCode;

public class GitLabApiException extends RuntimeException {

    private final HttpStatusCode statusCode;

    public GitLabApiException(String message,
                              HttpStatusCode statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public GitLabApiException(String message) {
        super(message);
        this.statusCode = null;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    public boolean isTransient() {
        if (statusCode == null) {
            return false;
        }
        int code = statusCode.value();
        return code == 429 || code >= 500 && code < 600;
    }
}
