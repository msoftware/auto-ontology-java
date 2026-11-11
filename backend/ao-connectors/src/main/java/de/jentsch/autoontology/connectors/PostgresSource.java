// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/** PostgreSQL (template: connectors/postgres.py). History from {@code pg_stat_activity}, last 24 h. */
public class PostgresSource extends JdbcSourceDatabase {
    public PostgresSource(String name, DataSource ds, AutoCloseable owned) {
        super(name, ds, owned);
    }

    @Override
    protected Set<String> systemSchemas() {
        return Connectors.merge(Connectors.COMMON_SYSTEM, Set.of("PG_CATALOG", "PG_TOAST"));
    }

    @Override
    protected boolean visibleSchema(String schema) {
        return super.visibleSchema(schema) && !schema.startsWith("pg_temp") && !schema.startsWith("pg_toast");
    }

    @Override
    public String dialect() {
        return "postgres";
    }

    @Override
    protected boolean foldsToLower() {
        return true;
    }

    @Override
    public List<Map<String, Object>> getQueries(int hours) {
        // state_change is the end time of the last statement of a backend, as in the original
        return optionalHistory("SELECT state_change AS end_time, query AS query_text FROM pg_stat_activity"
                + " WHERE state_change >= now() - interval '" + hours + " hours'"
                + " AND query IS NOT NULL AND query <> '' AND backend_type = 'client backend'"
                + " AND query NOT ILIKE '%pg_stat_activity%'");
    }
}
