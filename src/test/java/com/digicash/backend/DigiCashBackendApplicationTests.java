package com.digicash.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test confirming the full Spring application context starts
 * successfully, including the JPA/datasource auto-configuration against
 * the H2 test database (see src/test/resources/application.properties).
 */
@SpringBootTest
class DigiCashBackendApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: a failure to start the Spring context
        // (missing bean, bad datasource config, etc.) will fail this test
        // during context initialization before this method body even runs.
    }
}
