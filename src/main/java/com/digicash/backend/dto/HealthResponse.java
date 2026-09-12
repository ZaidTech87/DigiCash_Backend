package com.digicash.backend.dto;

/**
 * Response body for GET /api/health.
 *
 * Deliberately minimal - this endpoint exists only to confirm the
 * application context started and is reachable over HTTP; it performs no
 * database check or dependency probing in this phase.
 */
public class HealthResponse {

    private final String status;
    private final String message;

    public HealthResponse(String status, String message) {
        this.status = status;
        this.message = message;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
