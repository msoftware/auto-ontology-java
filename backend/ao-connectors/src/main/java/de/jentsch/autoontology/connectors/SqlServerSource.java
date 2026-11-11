// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Microsoft SQL Server (FR-CON-02): {@code [name]} quoting, {@code TOP n}, comments from
 * {@code MS_Description}, query history from the Query Store when it is enabled.
 */
public class SqlServerSource extends JdbcSourceDatabase {
    public SqlServerSource(String name, DataSource ds, AutoCloseable owned) {
        super(name, ds, owned);
    }

    @Override
    protected Set<String> systemSchemas() {
        return Set.of("INFORMATION_SCHEMA", "SYS", "GUEST", "DB_OWNER", "DB_ACCESSADMIN", "DB_SECURITYADMIN",
                "DB_DDLADMIN", "DB_BACKUPOPERATOR", "DB_DATAREADER", "DB_DATAWRITER", "DB_DENYDATAREADER",
                "DB_DENYDATAWRITER");
    }

    @Override
    public String dialect() {
        return "tsql";
    }

    @Override
    public String quote(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    @Override
    public String limit(String selectSql, int n) {
        return "SELECT TOP " + n + " * FROM (" + selectSql + ") AS ao_limited";
    }

    @Override
    public String sampleQuery(String schema, String table, int n) {
        return "SELECT TOP " + n + " * FROM " + qualify(schema, table);
    }

    @Override
    public List<Map<String, Object>> getColumnComments() {
        // JDBC REMARKS are empty on SQL Server; MS_Description lives in sys.extended_properties
        TabularResult r;
        try {
            r = execute("""
                    SELECT s.name, t.name, c.name, CAST(ep.value AS NVARCHAR(4000))
                    FROM sys.extended_properties ep
                    JOIN sys.objects t ON t.object_id = ep.major_id
                    JOIN sys.schemas s ON s.schema_id = t.schema_id
                    LEFT JOIN sys.columns c ON c.object_id = ep.major_id AND c.column_id = ep.minor_id
                    WHERE ep.name = 'MS_Description' AND ep.class = 1
                    """, List.of(), 30, 100000);
        } catch (RuntimeException e) {
            return List.of();
        }
        return r.rows().stream().map(row -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("table_schema", row.get(0));
            m.put("table_name", row.get(1));
            m.put("column_name", row.get(2));
            m.put("comment", row.get(3));
            return m;
        }).toList();
    }

    @Override
    public List<Map<String, Object>> getQueries(int hours) {
        return optionalHistory("SELECT rs.last_execution_time, qt.query_sql_text"
                + " FROM sys.query_store_query_text qt"
                + " JOIN sys.query_store_query q ON q.query_text_id = qt.query_text_id"
                + " JOIN sys.query_store_plan p ON p.query_id = q.query_id"
                + " JOIN sys.query_store_runtime_stats rs ON rs.plan_id = p.plan_id"
                + " WHERE rs.last_execution_time >= DATEADD(HOUR, -" + hours + ", SYSUTCDATETIME())");
    }
}
