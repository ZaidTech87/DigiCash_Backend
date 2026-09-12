package com.digicash.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the DIGICASH backend.
 *
 * This is a plain Spring Boot monolith - a single deployable JAR exposing
 * a REST API over MySQL via Spring Data JPA. It is intentionally kept
 * separate from the DigiCash Android project (different build tool,
 * different repository, different deployment target); the two projects
 * communicate only over HTTP, matching the Retrofit client already built
 * into the Android app (see Phase 0 analysis: POST /api/sync).
 *
 * No business logic (transactions, wallets, cryptographic verification)
 * exists yet as of this phase - see the individual package-info.java
 * files under entity/, repository/, service/, security/, and exception/
 * for what each is reserved for in later phases.
 */
@SpringBootApplication
public class DigiCashBackendApplication {

    public static void main(String[] args) {

        SpringApplication.run(DigiCashBackendApplication.class, args);
    }
}
