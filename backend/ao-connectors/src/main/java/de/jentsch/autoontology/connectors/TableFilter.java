// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import java.util.List;
import java.util.regex.Pattern;

/**
 * FR-CON-03 scoping, applied centrally at extraction (catalog/table_filter.py): schema allowlist,
 * allow/deny regex with {@code find()} on the unqualified, case-sensitive name; deny wins.
 */
public final class TableFilter {
    private final List<String> schemas;
    private final Pattern allow;
    private final Pattern deny;

    public TableFilter(List<String> schemas, String allowRegex, String denyRegex) {
        this.schemas = schemas == null ? List.of() : schemas;
        this.allow = blank(allowRegex) ? null : Pattern.compile(allowRegex);
        this.deny = blank(denyRegex) ? null : Pattern.compile(denyRegex);
    }

    public static TableFilter of(ConnectionSpec spec) {
        return new TableFilter(spec.schemas(), spec.tableAllowRegex(), spec.tableDenyRegex());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public boolean schemaAllowed(String schema) {
        return schemas.isEmpty() || schemas.stream().anyMatch(s -> s.equalsIgnoreCase(schema));
    }

    public boolean allows(String schema, String table) {
        if (!schemaAllowed(schema)) {
            return false;
        }
        if (deny != null && deny.matcher(table).find()) {
            return false;
        }
        return allow == null || allow.matcher(table).find();
    }
}
