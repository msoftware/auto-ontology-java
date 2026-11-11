// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

import java.util.List;

/** NFR-ARC-05 / NFR-QD-04: start-up validation of the configurable API prefixes. */
public final class ApiPrefixValidator {
    private static final List<String> RESERVED = List.of("/internal", "/mcp", "/actuator", "/h2-console");

    private ApiPrefixValidator() {}

    /**
     * Throws {@link IllegalStateException} with a clear message when the prefix is invalid.
     *
     * @param name property name for the message
     * @param prefix value to validate
     * @param dashboardPrefix {@code quasar-dashboard.api-prefix} if the dashboard runs in the same
     *     host, otherwise {@code null}
     */
    public static void validate(String name, String prefix, String dashboardPrefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException(name + " must not be empty");
        }
        if (!prefix.startsWith("/")) {
            throw new IllegalStateException(name + " must start with '/': " + prefix);
        }
        if (prefix.endsWith("/")) {
            throw new IllegalStateException(name + " must not end with '/': " + prefix);
        }
        for (String reserved : RESERVED) {
            if (prefix.equals(reserved) || prefix.startsWith(reserved + "/")) {
                throw new IllegalStateException(
                        name + " collides with reserved path " + reserved + ": " + prefix);
            }
        }
        if (dashboardPrefix != null
                && !dashboardPrefix.isBlank()
                && (prefix.equals(dashboardPrefix) || prefix.startsWith(dashboardPrefix + "/"))) {
            throw new IllegalStateException(name + " (" + prefix
                    + ") collides with quasar-dashboard.api-prefix (" + dashboardPrefix + ")");
        }
    }
}
