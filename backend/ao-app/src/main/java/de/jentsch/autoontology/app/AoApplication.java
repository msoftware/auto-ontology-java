// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone Auto-Ontology host (profiles default, demo, worker, qdrant). Internal tool without
 * authentication: binds to 127.0.0.1 by default and must not be exposed to the internet
 * (NFR-SEC-06).
 */
@SpringBootApplication
public class AoApplication {
    public static void main(String[] args) {
        SpringApplication.run(AoApplication.class, args);
    }
}
