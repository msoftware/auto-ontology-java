// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.store;

import de.jentsch.autoontology.common.Rows;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Thin wrapper around {@link JdbcClient}: normalized row maps and typed parameters. */
public class StoreJdbc {
    private final JdbcClient jdbc;

    public StoreJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcClient client() {
        return jdbc;
    }

    public List<Map<String, Object>> list(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(convert(params)).query().listOfRows().stream()
                .map(Rows::normalize)
                .toList();
    }

    public List<Map<String, Object>> list(String sql) {
        return list(sql, Map.of());
    }

    public Optional<Map<String, Object>> one(String sql, Map<String, ?> params) {
        List<Map<String, Object>> rows = list(sql, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public int update(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(convert(params)).update();
    }

    public long count(String sql, Map<String, ?> params) {
        Object v = jdbc.sql(sql).params(convert(params)).query().singleValue();
        return v == null ? 0 : ((Number) v).longValue();
    }

    public <T> List<T> column(String sql, Map<String, ?> params, Class<T> type) {
        return jdbc.sql(sql).params(convert(params)).query(type).list();
    }

    /** Instants become timestamps; collections stay (for IN lists). */
    private static Map<String, Object> convert(Map<String, ?> params) {
        Map<String, Object> out = new java.util.HashMap<>();
        params.forEach((k, v) -> out.put(k, convertValue(v)));
        return out;
    }

    private static Object convertValue(Object v) {
        if (v instanceof Instant i) {
            return Timestamp.from(i);
        }
        if (v instanceof Collection<?> c && c.isEmpty()) {
            // an empty IN list is invalid SQL; use a value that never matches an ID
            return List.of("__none__");
        }
        return v;
    }
}
