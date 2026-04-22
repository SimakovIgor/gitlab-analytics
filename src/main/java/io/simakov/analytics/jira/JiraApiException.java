package io.simakov.analytics.jira;

public class JiraApiException extends RuntimeException {

    public JiraApiException(String message) {
        super(message);
    }
}
