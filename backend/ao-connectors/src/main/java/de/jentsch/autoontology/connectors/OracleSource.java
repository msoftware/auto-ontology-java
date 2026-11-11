// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Oracle (FR-CON-02): unquoted identifiers in upper case, {@code FETCH FIRST n ROWS ONLY}, schema
 * = owner. Query history from {@code V$SQL} only with the necessary rights; AWR is not used
 * (Diagnostics Pack licence).
 */
public class OracleSource extends JdbcSourceDatabase {
    static final Set<String> SYSTEM = Set.of("SYS", "SYSTEM", "XDB", "OUTLN", "DBSNMP", "APPQOSSYS", "AUDSYS", "CTXSYS",
            "DVSYS", "DBSFWUSER", "GGSYS", "GSMADMIN_INTERNAL", "LBACSYS", "MDSYS", "OJVMSYS", "OLAPSYS", "ORDDATA",
            "ORDSYS", "ORDPLUGINS", "SI_INFORMTN_SCHEMA", "WMSYS", "REMOTE_SCHEDULER_AGENT", "DIP", "ANONYMOUS",
            "GSMCATUSER", "GSMUSER", "SYSBACKUP", "SYSDG", "SYSKM", "SYSRAC", "SYS$UMF", "XS$NULL", "MDDATA",
            "DVF", "ORACLE_OCM", "PDBADMIN", "GSMROOTUSER", "OPS$ORACLE", "VECSYS", "DGPDB_INT");

    public OracleSource(String name, DataSource ds, AutoCloseable owned) {
        super(name, ds, owned);
    }

    @Override
    protected Set<String> systemSchemas() {
        return SYSTEM;
    }

    @Override
    protected boolean visibleSchema(String schema) {
        return super.visibleSchema(schema) && !schema.startsWith("APEX_") && !schema.startsWith("FLOWS_");
    }

    @Override
    public String dialect() {
        return "oracle";
    }

    @Override
    protected boolean foldsToLower() {
        return false;
    }

    @Override
    public String limit(String selectSql, int n) {
        return "SELECT * FROM (" + selectSql + ") FETCH FIRST " + n + " ROWS ONLY";
    }

    @Override
    public String sampleQuery(String schema, String table, int n) {
        return "SELECT * FROM " + qualify(schema, table) + " FETCH FIRST " + n + " ROWS ONLY";
    }

    @Override
    public List<Map<String, Object>> getQueries(int hours) {
        return optionalHistory("SELECT CAST(TO_DATE(last_load_time, 'YYYY-MM-DD/HH24:MI:SS') AS TIMESTAMP) AS end_time,"
                + " sql_fulltext AS query_text FROM v$sql WHERE command_type = 3"
                + " AND parsing_schema_name NOT IN ('SYS', 'SYSTEM')"
                + " AND TO_DATE(last_load_time, 'YYYY-MM-DD/HH24:MI:SS') >= SYSDATE - " + hours + "/24");
    }
}
