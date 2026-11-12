// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** FR-CON-05: the read-only check walks the whole statement, not only its top-level type. */
class ReadOnlySqlTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT a FROM t",
        "SELECT a FROM t;",
        "WITH x AS (SELECT a FROM t) SELECT * FROM x",
        "SELECT a FROM t UNION ALL SELECT a FROM u",
        "SELECT * FROM (SELECT a, COUNT(*) AS n FROM t GROUP BY a) s WHERE s.n > 1",
        "SELECT a FROM t WHERE b IN (SELECT b FROM u)"
    })
    void acceptsPlainQueries(String sql) {
        assertThat(ReadOnlySql.violation(sql)).isNull();
        assertThat(ReadOnlySql.isReadOnlySelect(sql)).isTrue();
    }

    /** JSqlParser parses these as a Select although they write: PostgreSQL would execute the DML. */
    @ParameterizedTest
    @ValueSource(strings = {
        "WITH d AS (DELETE FROM t WHERE id = 1 RETURNING *) SELECT * FROM d",
        "WITH d AS (INSERT INTO t (a) VALUES (1) RETURNING *) SELECT * FROM d",
        "WITH d AS (UPDATE t SET a = 1 RETURNING *) SELECT * FROM d"
    })
    void rejectsDataModifyingCtes(String sql) {
        assertThat(ReadOnlySql.violation(sql)).isEqualTo("data-modifying statements in WITH are not allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT a FROM t FOR UPDATE",
        "(SELECT a FROM t FOR UPDATE)",
        "SELECT * FROM (SELECT a FROM t FOR UPDATE) x",
        "WITH x AS (SELECT a FROM t FOR UPDATE) SELECT * FROM x"
    })
    void rejectsLockingReadsAtEveryLevel(String sql) {
        assertThat(ReadOnlySql.violation(sql)).isEqualTo("SELECT ... FOR UPDATE is not allowed");
    }

    @Test
    void rejectsEverythingElse() {
        assertThat(ReadOnlySql.violation("DELETE FROM t")).isEqualTo("only SELECT/WITH statements are allowed");
        assertThat(ReadOnlySql.violation("SELECT 1; DROP TABLE t")).isEqualTo("only a single statement is allowed");
        assertThat(ReadOnlySql.violation("SELECT a INTO copy FROM t")).isEqualTo("SELECT INTO is not allowed");
        assertThat(ReadOnlySql.violation("  ")).isEqualTo("empty SQL");
        assertThat(ReadOnlySql.violation("SELEC a FROM")).startsWith("SQL could not be parsed");
    }

    @Test
    void namesFromJdbcUrls() {
        assertThat(Connectors.nameFromUrl("jdbc:h2:mem:demo_sales;DB_CLOSE_DELAY=-1")).isEqualTo("demo_sales");
        assertThat(Connectors.nameFromUrl("jdbc:postgresql://db:5432/shop?user=x")).isEqualTo("shop");
        assertThat(Connectors.nameFromUrl("jdbc:sqlserver://db:1433;databaseName=crm;encrypt=true")).isEqualTo("crm");
        assertThat(Connectors.nameFromUrl("jdbc:oracle:thin:@//db:1521/ORCLPDB1")).isEqualTo("ORCLPDB1");
        assertThat(Connectors.nameFromUrl("jdbc:h2:file:C:\\data\\sales")).isEqualTo("sales");
    }
}
