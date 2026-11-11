// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Embedded PostgreSQL in the standard build, without Docker (NFR-TST-02). */
class PostgresSourceTest extends ConnectorContract {
    static EmbeddedPostgres pg;
    static DataSource ds;

    @BeforeAll
    static void start() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        ds = pg.getPostgresDatabase();
        for (String stmt : H2SourceTest.DDL.split(";\\s*\n")) {
            if (!stmt.isBlank()) {
                JdbcClient.create(ds).sql(stmt).update();
            }
        }
    }

    @AfterAll
    static void stop() throws Exception {
        pg.close();
    }

    @Override
    protected SourceDatabase source() {
        return Connectors.wrap("shop_db", ds);
    }

    @Override
    protected String expectedDialect() {
        return "postgres";
    }
}
