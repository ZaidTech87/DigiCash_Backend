package com.digicash.backend.controller;

import com.digicash.backend.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Basic liveness endpoint for the DIGICASH backend.
 *
 * GET /api/health - returns a simple static success response confirming
 * the application is running and reachable. No transaction, wallet, or
 * cryptographic logic is implemented in this phase.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "DigiCash backend is running");
    }
}
