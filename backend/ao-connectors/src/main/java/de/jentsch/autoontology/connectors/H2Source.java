// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * H2 2.x (FR-DEMO-03). Works with and without {@code DATABASE_TO_LOWER=TRUE} (FR-NLC-01). The
 * query history is read from {@code DEMO_META.QUERY_HISTORY(END_TIME, QUERY_TEXT)} when present.
 */
public class H2Source extends JdbcSourceDatabase {
    public H2Source(String name, DataSource ds, AutoCloseable owned) {
        super(name, ds, owned);
    }

    @Override
    protected Set<String> systemSchemas() {
        return Connectors.COMMON_SYSTEM;
    }

    @Override
    public String dialect() {
        return "h2";
    }

    @Override
    public List<Map<String, Object>> getQueries(int hours) {
        String schema = quote(foldsToLower() ? "demo_meta" : "DEMO_META");
        String table = quote(foldsToLower() ? "query_history" : "QUERY_HISTORY");
        String end = quote(foldsToLower() ? "end_time" : "END_TIME");
        String text = quote(foldsToLower() ? "query_text" : "QUERY_TEXT");
        boolean exists = getTables().stream().anyMatch(t -> "DEMO_META".equalsIgnoreCase((String) t.get("table_schema"))
                && "QUERY_HISTORY".equalsIgnoreCase((String) t.get("table_name")));
        if (!exists) {
            return List.of();
        }
        return optionalHistory("SELECT " + end + ", " + text + " FROM " + schema + "." + table + " WHERE " + end
                + " >= DATEADD('HOUR', -" + hours + ", CURRENT_TIMESTAMP) ORDER BY " + end);
    }
}
