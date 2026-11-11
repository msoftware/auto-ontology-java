// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import de.jentsch.autoontology.common.CancellationToken;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connector base on JDBC. Catalog metadata comes from {@link DatabaseMetaData} (which reads
 * information_schema / ALL_* / sys views of the engine); dialect subclasses add query history,
 * quoting and row caps.
 */
public abstract class JdbcSourceDatabase implements SourceDatabase {
    private static final Logger log = LoggerFactory.getLogger(JdbcSourceDatabase.class);

    protected final DataSource dataSource;
    protected final String databaseName;
    private final AutoCloseable owned;
    private volatile Boolean lowerCaseIdentifiers;

    protected JdbcSourceDatabase(String databaseName, DataSource dataSource, AutoCloseable owned) {
        this.databaseName = databaseName;
        this.dataSource = dataSource;
        this.owned = owned;
    }

    /** Schemas that are never part of the catalog. */
    protected abstract Set<String> systemSchemas();

    @Override
    public String databaseName() {
        return databaseName;
    }

    // ------------------------------------------------------------------ execution

    @Override
    public TabularResult execute(String sql, List<Object> params, int timeoutSeconds, int maxRows) {
        CancellationToken token = CancellationToken.current();
        if (token != null) {
            token.check();
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (timeoutSeconds > 0) {
                ps.setQueryTimeout(timeoutSeconds);
            }
            if (maxRows > 0) {
                // one extra row tells whether the result was truncated
                ps.setMaxRows(maxRows + 1);
            }
            if (params != null) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
            }
            CancellationToken.Scope hook = token == null ? () -> {} : token.onCancel(() -> cancelQuietly(ps));
            try (hook; ResultSet rs = ps.executeQuery()) {
                return read(rs, maxRows);
            }
        } catch (SQLException e) {
            if (token != null && token.isCancelled()) {
                throw new CancellationToken.CancelledException();
            }
            throw new DbErrors.SourceQueryException(e);
        }
    }

    private static void cancelQuietly(PreparedStatement ps) {
        try {
            ps.cancel();
        } catch (SQLException e) {
            // statement already finished
        }
    }

    protected TabularResult read(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> cols = new ArrayList<>(n);
        List<Integer> types = new ArrayList<>(n);
        Set<String> seen = new HashSet<>();
        for (int i = 1; i <= n; i++) {
            String base = md.getColumnLabel(i);
            String name = base;
            int k = 2;
            while (!seen.add(name.toLowerCase(Locale.ROOT))) {
                name = base + "_" + k++;
            }
            cols.add(name);
            types.add(md.getColumnType(i));
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (maxRows > 0 && rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                row.add(normalize(rs.getObject(i)));
            }
            rows.add(row);
        }
        return new TabularResult(cols, types, rows, truncated);
    }

    /** JSON-friendly values. */
    protected static Object normalize(Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof Clob clob) {
            return clob.getSubString(1, (int) Math.min(clob.length(), 1_000_000));
        }
        if (v instanceof Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (v instanceof OffsetDateTime o) {
            return o.toString();
        }
        if (v instanceof java.time.temporal.Temporal t) {
            return t.toString();
        }
        if (v instanceof BigDecimal bd) {
            return bd.scale() <= 0 && bd.precision() < 19 ? (Object) bd.longValue() : bd;
        }
        if (v instanceof Array a) {
            Object arr = a.getArray();
            if (arr instanceof Object[] oa) {
                List<Object> out = new ArrayList<>();
                for (Object o : oa) {
                    out.add(normalize(o));
                }
                return out;
            }
            return String.valueOf(arr);
        }
        if (v instanceof byte[] b) {
            return "<" + b.length + " bytes>";
        }
        if (v instanceof java.util.UUID u) {
            return u.toString();
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof String) {
            return v;
        }
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------ catalog

    private interface MetaCall {
        ResultSet call(DatabaseMetaData md) throws SQLException;
    }

    private interface RowMapper {
        Map<String, Object> map(ResultSet rs) throws SQLException;
    }

    private List<Map<String, Object>> meta(MetaCall call, RowMapper mapper) {
        try (Connection c = dataSource.getConnection(); ResultSet rs = call.call(c.getMetaData())) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = mapper.map(rs);
                if (row != null) {
                    out.add(row);
                }
            }
            return out;
        } catch (SQLException e) {
            throw new DbErrors.SourceQueryException(e);
        }
    }

    protected String catalogFilter() {
        return null;
    }

    protected boolean visibleSchema(String schema) {
        return schema != null && !systemSchemas().contains(schema.toUpperCase(Locale.ROOT));
    }

    @Override
    public List<String> listSchemas() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : meta(DatabaseMetaData::getSchemas, rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("s", rs.getString("TABLE_SCHEM"));
            return m;
        })) {
            String s = (String) r.get("s");
            if (visibleSchema(s)) {
                out.add(s);
            }
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> getTables() {
        return meta(md -> md.getTables(catalogFilter(), null, "%", new String[] {"TABLE", "BASE TABLE", "VIEW",
            "MATERIALIZED VIEW"}), rs -> {
                String schema = rs.getString("TABLE_SCHEM");
                if (!visibleSchema(schema)) {
                    return null;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table_schema", schema);
                m.put("table_name", rs.getString("TABLE_NAME"));
                m.put("table_type", canonicalType(rs.getString("TABLE_TYPE")));
                m.put("remarks", rs.getString("REMARKS"));
                return m;
            });
    }

    static String canonicalType(String jdbcType) {
        String t = jdbcType == null ? "" : jdbcType.toUpperCase(Locale.ROOT);
        if (t.contains("MATERIALIZED")) {
            return "materialized view";
        }
        if (t.contains("VIEW")) {
            return "view";
        }
        return "base table";
    }

    @Override
    public List<Map<String, Object>> getColumns() {
        return meta(md -> md.getColumns(catalogFilter(), null, "%", "%"), rs -> {
            String schema = rs.getString("TABLE_SCHEM");
            if (!visibleSchema(schema)) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table_schema", schema);
            m.put("table_name", rs.getString("TABLE_NAME"));
            m.put("column_name", rs.getString("COLUMN_NAME"));
            m.put("data_type", dataType(rs.getString("TYPE_NAME"), rs.getInt("DATA_TYPE")));
            // a real boolean, never "YES"/"NO" (FR-CON-01)
            m.put("is_nullable", rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
            m.put("ordinal_position", rs.getInt("ORDINAL_POSITION"));
            m.put("remarks", rs.getString("REMARKS"));
            return m;
        });
    }

    protected String dataType(String typeName, int jdbcType) {
        if (typeName != null && !typeName.isBlank()) {
            return typeName.toLowerCase(Locale.ROOT);
        }
        return jdbcType == Types.OTHER ? "other" : String.valueOf(jdbcType);
    }

    @Override
    public List<Map<String, Object>> getViews() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : getTables()) {
            if (!"base table".equals(t.get("table_type"))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table_schema", t.get("table_schema"));
                m.put("table_name", t.get("table_name"));
                // extracted like the original but not evaluated further (FR-CON-01 notes)
                m.put("view_definition", null);
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> getPks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : getTables()) {
            if (!"base table".equals(t.get("table_type"))) {
                continue;
            }
            String schema = (String) t.get("table_schema");
            String table = (String) t.get("table_name");
            out.addAll(meta(md -> md.getPrimaryKeys(catalogFilter(), schema, table), rs -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table_schema", rs.getString("TABLE_SCHEM"));
                m.put("table_name", rs.getString("TABLE_NAME"));
                m.put("column_name", rs.getString("COLUMN_NAME"));
                m.put("ordinal_position", rs.getInt("KEY_SEQ"));
                return m;
            }));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> getFks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : getTables()) {
            if (!"base table".equals(t.get("table_type"))) {
                continue;
            }
            String schema = (String) t.get("table_schema");
            String table = (String) t.get("table_name");
            out.addAll(meta(md -> md.getImportedKeys(catalogFilter(), schema, table), rs -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table_schema", rs.getString("FKTABLE_SCHEM"));
                m.put("table_name", rs.getString("FKTABLE_NAME"));
                m.put("column_name", rs.getString("FKCOLUMN_NAME"));
                m.put("referenced_schema", rs.getString("PKTABLE_SCHEM"));
                m.put("referenced_table", rs.getString("PKTABLE_NAME"));
                m.put("referenced_column", rs.getString("PKCOLUMN_NAME"));
                return m;
            }));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> getColumnComments() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : getTables()) {
            if (t.get("remarks") != null && !((String) t.get("remarks")).isBlank()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table_schema", t.get("table_schema"));
                m.put("table_name", t.get("table_name"));
                m.put("column_name", null);
                m.put("comment", t.get("remarks"));
                out.add(m);
            }
        }
        for (Map<String, Object> c : getColumns()) {
            if (c.get("remarks") != null && !((String) c.get("remarks")).isBlank()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table_schema", c.get("table_schema"));
                m.put("table_name", c.get("table_name"));
                m.put("column_name", c.get("column_name"));
                m.put("comment", c.get("remarks"));
                out.add(m);
            }
        }
        return out;
    }

    /** Runs a history query and swallows missing rights or features (empty history). */
    protected List<Map<String, Object>> optionalHistory(String sql) {
        try {
            TabularResult r = execute(sql, List.of(), 30, 5000);
            List<Map<String, Object>> out = new ArrayList<>();
            for (List<Object> row : r.rows()) {
                if (row.get(1) == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("end_time", row.get(0));
                m.put("query_text", String.valueOf(row.get(1)));
                out.add(m);
            }
            return out;
        } catch (RuntimeException e) {
            log.info("Query history of {} not available: {}", databaseName, e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ quoting

    /** Whether unquoted identifiers fold to lower case (PostgreSQL, H2 with DATABASE_TO_LOWER). */
    protected boolean foldsToLower() {
        Boolean v = lowerCaseIdentifiers;
        if (v == null) {
            try (Connection c = dataSource.getConnection()) {
                v = c.getMetaData().storesLowerCaseIdentifiers();
            } catch (SQLException e) {
                v = false;
            }
            lowerCaseIdentifiers = v;
        }
        return v;
    }

    @Override
    public String quote(String identifier) {
        String folded = foldsToLower() ? identifier.toLowerCase(Locale.ROOT) : identifier.toUpperCase(Locale.ROOT);
        if (identifier.equals(folded) && identifier.matches("[A-Za-z_][A-Za-z0-9_]*") && !reserved(identifier)) {
            return identifier;
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    protected boolean reserved(String identifier) {
        return Set.of("USER", "ORDER", "GROUP", "TABLE", "SELECT", "FROM", "WHERE", "VALUE", "KEY", "YEAR", "MONTH",
                "DAY", "DATE", "TIME", "RANK", "LIMIT").contains(identifier.toUpperCase(Locale.ROOT));
    }

    @Override
    public String qualify(String schema, String table) {
        return schema == null || schema.isBlank() ? quote(table) : quote(schema) + "." + quote(table);
    }

    @Override
    public String sampleQuery(String schema, String table, int n) {
        return limit("SELECT * FROM " + qualify(schema, table), n);
    }

    @Override
    public String limit(String selectSql, int n) {
        return selectSql + " LIMIT " + n;
    }

    @Override
    public void close() {
        if (owned != null) {
            try {
                owned.close();
            } catch (Exception e) {
                log.debug("Closing connector {} failed: {}", databaseName, e.getMessage());
            }
        }
    }
}
