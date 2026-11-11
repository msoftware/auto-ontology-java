// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Row helpers: lower-case keys and JSON-friendly values. */
public final class Rows {
    private Rows() {}

    public static Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), value(v)));
        return out;
    }

    public static Object value(Object v) {
        if (v instanceof Clob clob) {
            try {
                return clob.getSubString(1, (int) clob.length());
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        if (v instanceof Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toInstant().toString();
        }
        return v;
    }

    public static String str(Map<String, ?> row, String key) {
        Object v = row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static boolean bool(Map<String, ?> row, String key) {
        Object v = row.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }
}
