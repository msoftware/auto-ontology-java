// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import java.util.List;

/**
 * A source connection: JDBC URL with credentials or a DataSource bean (FR-NLC-01), plus scoping
 * (FR-CON-03). Credentials never appear in {@link #toString()} (NFR-SEC-02).
 */
public record ConnectionSpec(
        String name,
        String url,
        String username,
        String password,
        String datasourceBean,
        List<String> schemas,
        String tableAllowRegex,
        String tableDenyRegex) {

    public ConnectionSpec {
        schemas = schemas == null ? List.of() : List.copyOf(schemas);
    }

    public ConnectionSpec withName(String newName) {
        return new ConnectionSpec(newName, url, username, password, datasourceBean, schemas, tableAllowRegex,
                tableDenyRegex);
    }

    /** URL with password-like parameters masked (registry.py::_redact). */
    public String redactedUrl() {
        return redact(url);
    }

    public static String redact(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("(?i)(password|pwd|secret|token)=([^;&]*)", "$1=***")
                .replaceAll("://([^:/@]+):([^@]+)@", "://$1:***@");
    }

    @Override
    public String toString() {
        return "ConnectionSpec[name=" + name + ", url=" + redactedUrl() + ", bean=" + datasourceBean + ", schemas="
                + schemas + "]";
    }
}
