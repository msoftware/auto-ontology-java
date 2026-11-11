// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a statement: column names, JDBC type codes and rows. Column names are made unique
 * ({@code name}, {@code name_2}, ...) like {@code sql_execution.py} does.
 */
public record TabularResult(List<String> columns, List<Integer> jdbcTypes, List<List<Object>> rows,
        boolean truncated) {

    public List<Map<String, Object>> asMaps() {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (List<Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                m.put(columns.get(i), r.get(i));
            }
            out.add(m);
        }
        return out;
    }

    /** First column as list (convenience for metadata queries). */
    public List<Object> column(int index) {
        List<Object> out = new ArrayList<>(rows.size());
        for (List<Object> r : rows) {
            out.add(r.get(index));
        }
        return out;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
