// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * NFR-TST-02: every connector fulfils the same contract (columns, types, boolean is_nullable, FKs,
 * PKs, views, sample query, read-only execution).
 */
abstract class ConnectorContract {
    /** Connector on a database with schema {@code shop}: customers, orders (FK), v_orders (view). */
    protected abstract SourceDatabase source();

    protected abstract String expectedDialect();

    private static String norm(Object o) {
        return String.valueOf(o).toLowerCase(Locale.ROOT);
    }

    @Test
    void catalogMetadata() {
        SourceDatabase db = source();
        assertThat(db.dialect()).isEqualTo(expectedDialect());
        List<Map<String, Object>> tables = db.getTables().stream()
                .filter(t -> norm(t.get("table_schema")).equals("shop")).toList();
        assertThat(tables).extracting(t -> norm(t.get("table_name")))
                .containsExactlyInAnyOrder("customers", "orders", "v_orders");
        assertThat(tables).filteredOn(t -> norm(t.get("table_name")).equals("v_orders"))
                .extracting(t -> t.get("table_type")).containsExactly("view");

        List<Map<String, Object>> cols = db.getColumns().stream()
                .filter(c -> norm(c.get("table_name")).equals("customers")).toList();
        assertThat(cols).extracting(c -> norm(c.get("column_name"))).contains("customer_id", "customer_name");
        Map<String, Object> id = cols.stream().filter(c -> norm(c.get("column_name")).equals("customer_id"))
                .findFirst().orElseThrow();
        assertThat(id.get("is_nullable")).isEqualTo(Boolean.FALSE);
        assertThat(id.get("ordinal_position")).isEqualTo(1);

        assertThat(db.getPks()).anySatisfy(pk -> {
            assertThat(norm(pk.get("table_name"))).isEqualTo("customers");
            assertThat(norm(pk.get("column_name"))).isEqualTo("customer_id");
        });
        assertThat(db.getFks()).anySatisfy(fk -> {
            assertThat(norm(fk.get("table_name"))).isEqualTo("orders");
            assertThat(norm(fk.get("referenced_table"))).isEqualTo("customers");
            assertThat(norm(fk.get("referenced_column"))).isEqualTo("customer_id");
        });
        assertThat(db.getViews()).extracting(v -> norm(v.get("table_name"))).contains("v_orders");
        assertThat(db.listSchemas()).extracting(ConnectorContract::norm).contains("shop");
    }

    @Test
    void sampleQueryAndExecution() {
        SourceDatabase db = source();
        String schema = db.getTables().stream().filter(t -> norm(t.get("table_schema")).equals("shop")).findFirst()
                .map(t -> (String) t.get("table_schema")).orElseThrow();
        String table = db.getTables().stream().filter(t -> norm(t.get("table_name")).equals("customers")).findFirst()
                .map(t -> (String) t.get("table_name")).orElseThrow();
        TabularResult r = db.execute(db.sampleQuery(schema, table, 2), List.of(), 30, 100);
        assertThat(r.rows()).hasSize(2);
        TabularResult capped = db.execute("SELECT * FROM " + db.qualify(schema, table), List.of(), 30, 1);
        assertThat(capped.rows()).hasSize(1);
        assertThat(capped.truncated()).isTrue();
        assertThatThrownBy(() -> db.execute("SELECT * FROM no_such_table_x", List.of(), 30, 10))
                .isInstanceOf(DbErrors.SourceQueryException.class);
    }

    @Test
    void readOnlyGuard() {
        assertThat(ReadOnlySql.isReadOnlySelect("WITH x AS (SELECT 1 AS a) SELECT a FROM x;")).isTrue();
        assertThat(ReadOnlySql.violation("DELETE FROM shop.customers")).contains("only SELECT");
        assertThat(ReadOnlySql.violation("SELECT 1; SELECT 2")).contains("single");
    }
}
