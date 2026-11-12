// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Static phase of validate_sql_query: catalog names, read-only rule, no false alarms. */
class SqlStaticChecksTest {

    private static SqlStaticChecks.CatalogView catalog(String dialect) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String c : List.of("ORDER_ID", "CUSTOMER_ID", "ORDER_DATE", "AMOUNT")) {
            rows.add(Map.of("schema_name", "SALES", "table_name", "ORDERS", "name", c));
        }
        for (String c : List.of("CUSTOMER_ID", "CUSTOMER_NAME")) {
            rows.add(Map.of("schema_name", "SALES", "table_name", "CUSTOMERS", "name", c));
        }
        return SqlStaticChecks.view(rows, dialect);
    }

    @Test
    void validJoinReportsUsedObjects() {
        SqlStaticChecks.Result r = SqlStaticChecks.check("""
                SELECT c.CUSTOMER_NAME, SUM(o.AMOUNT) AS revenue
                FROM SALES.ORDERS o JOIN SALES.CUSTOMERS c ON c.CUSTOMER_ID = o.CUSTOMER_ID
                GROUP BY c.CUSTOMER_NAME ORDER BY revenue DESC""", catalog("h2"));
        assertThat(r.errors()).isEmpty();
        assertThat(r.tables()).containsExactlyInAnyOrder("sales.orders", "sales.customers");
        assertThat(r.columns()).contains("sales.orders.AMOUNT", "sales.customers.CUSTOMER_NAME");
    }

    @Test
    void unknownNamesAreErrors() {
        assertThat(SqlStaticChecks.check("SELECT x FROM SALES.INVOICES", catalog("h2")).errors())
                .anyMatch(e -> e.contains("Table SALES.INVOICES does not exist"));
        assertThat(SqlStaticChecks.check("SELECT o.TOTAL FROM SALES.ORDERS o", catalog("h2")).errors())
                .containsExactly("Column TOTAL does not exist in table orders.");
        assertThat(SqlStaticChecks.check("SELECT TOTAL FROM SALES.ORDERS", catalog("h2")).errors())
                .containsExactly("Column TOTAL does not exist in any referenced table.");
    }

    /** SYSDATE and ROWNUM look like columns to the parser; a correct Oracle statement must pass. */
    @Test
    void pseudoColumnsOfTheEngineAreNotCatalogColumns() {
        assertThat(SqlStaticChecks.check(
                "SELECT ORDER_ID FROM SALES.ORDERS WHERE ORDER_DATE >= SYSDATE - 30 AND ROWNUM <= 5", catalog("oracle"))
                .errors()).isEmpty();
    }

    /** An alias declared without AS is still an alias, not a missing column. */
    @Test
    void aliasesWithoutAsAreKnown() {
        assertThat(SqlStaticChecks.check(
                "SELECT CUSTOMER_ID, COUNT(*) n FROM SALES.ORDERS GROUP BY CUSTOMER_ID ORDER BY n DESC", catalog("h2"))
                .errors()).isEmpty();
    }

    @Test
    void writesAndDegeneratePredicatesAreRejected() {
        assertThat(SqlStaticChecks.check("DELETE FROM SALES.ORDERS", catalog("h2")).errors())
                .containsExactly("only SELECT/WITH statements are allowed");
        assertThat(SqlStaticChecks.check(
                "WITH d AS (DELETE FROM SALES.ORDERS RETURNING *) SELECT * FROM d", catalog("postgres")).errors())
                .containsExactly("data-modifying statements in WITH are not allowed");
        assertThat(SqlStaticChecks.check("SELECT ORDER_ID FROM SALES.ORDERS WHERE 1 = 0", catalog("h2")).errors())
                .anyMatch(e -> e.startsWith("Degenerate predicate"));
    }

    @Test
    void postgresNeedsQuotesForMixedCase() {
        assertThat(SqlStaticChecks.check("SELECT AMOUNT FROM SALES.ORDERS", catalog("postgres")).errors())
                .anyMatch(e -> e.contains("must be double-quoted on PostgreSQL"));
        assertThat(SqlStaticChecks.check("SELECT \"AMOUNT\" FROM SALES.ORDERS", catalog("postgres")).errors()).isEmpty();
    }
}
