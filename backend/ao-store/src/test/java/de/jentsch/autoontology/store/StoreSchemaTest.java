// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;

/** FR-DB-01/02: the Liquibase schema runs on H2 and PostgreSQL; repositories work on both. */
class StoreSchemaTest {
    private static EmbeddedPostgres postgres;

    static Stream<DataSource> stores() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:store_" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        if (postgres == null) {
            postgres = EmbeddedPostgres.builder().start();
        }
        JdbcDataSource h2Upper = new JdbcDataSource();
        h2Upper.setURL("jdbc:h2:mem:store_u" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return Stream.of(h2, h2Upper, postgres.getPostgresDatabase());
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    private static StoreJdbc migrate(DataSource ds) throws Exception {
        SpringLiquibase lb = new AoSpringLiquibase();
        lb.setDataSource(ds);
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.afterPropertiesSet();
        return new StoreJdbc(JdbcClient.create(ds));
    }

    /** A restart runs the migration again on an existing store and must not recreate the changelog tables. */
    @ParameterizedTest
    @MethodSource("stores")
    void migrationIsRepeatable(DataSource ds) throws Exception {
        // H2 with DATABASE_TO_LOWER cannot be migrated twice; AoSpringLiquibase rejects it for persistent stores
        assumeFalse(ds instanceof JdbcDataSource h && h.getURL().contains("DATABASE_TO_LOWER"));
        migrate(ds);
        StoreJdbc db = migrate(ds);
        Integer runs = db.client().sql("SELECT COUNT(*) FROM AO_DATABASECHANGELOG").query(Integer.class).single();
        assertThat(runs).isPositive();
    }

    @ParameterizedTest
    @MethodSource("stores")
    void catalogAndSemanticRoundTrip(DataSource ds) throws Exception {
        StoreJdbc db = migrate(ds);
        CatalogRepository catalog = new CatalogRepository(db);
        SemanticRepository semantic = new SemanticRepository(db);
        Instant now = Instant.parse("2026-06-30T00:00:00Z");
        String dbName = "demo_" + System.nanoTime();

        String dbId = catalog.upsertDatabase(dbName, "h2", Map.of("url", "jdbc:h2:mem:x"), now);
        assertThat(catalog.upsertDatabase(dbName, "h2", null, now)).isEqualTo(dbId);
        String schemaId = catalog.upsertSchema(dbId, "SALES");
        String tableId = catalog.upsertTable(schemaId, "CUSTOMERS", "base table", List.of("CUSTOMER_ID"), "Customers");
        String colId = catalog.upsertColumn(tableId, "CUSTOMER_ID", "INTEGER", false, 1, null);
        catalog.upsertColumn(tableId, "CUSTOMER_NAME", "VARCHAR", true, 2, null);

        Map<String, Object> table = catalog.tableById(tableId).orElseThrow();
        assertThat(table).containsEntry("schema_name", "SALES").containsEntry("database_name", dbName);
        assertThat(table.get("pk")).isEqualTo(List.of("CUSTOMER_ID"));
        List<Map<String, Object>> cols = catalog.columnsOfTable(tableId);
        assertThat(cols).hasSize(2);
        // is_nullable is a real boolean (FR-CON-01)
        assertThat(cols.get(0).get("is_nullable")).isEqualTo(Boolean.FALSE);
        assertThat(catalog.tablesWithoutTerm(dbId)).hasSize(1);

        String termId = semantic.mergeTerm("Customer", "A customer", List.of("Client"), "semantic", now);
        assertThat(semantic.mergeTerm("Customer", "other", List.of("Buyer"), "semantic", now)).isEqualTo(termId);
        semantic.linkTableTerm(tableId, termId);
        String attrId = semantic.mergeColumnAttribute("Customer Id", "", "INTEGER", "CUSTOMER_ID", "Customer",
                termId, tableId, colId, now);
        assertThat(semantic.termById(termId).orElseThrow().get("synonyms")).isEqualTo(List.of("Client", "Buyer"));
        assertThat(semantic.attributesOfTerm(termId)).extracting(r -> r.get("id")).containsExactly(attrId);
        assertThat(catalog.tablesWithoutTerm(dbId)).isEmpty();
        assertThat(semantic.termCounters(termId)).containsEntry("table_count", 1L);

        // reset of one database keeps nothing of its semantic layer
        semantic.deleteSemanticLayer(dbId);
        assertThat(semantic.termById(termId)).isEmpty();
        assertThat(catalog.deleteDatabase(dbId)).isEqualTo(1);
        assertThat(catalog.tableById(tableId)).isEmpty();
    }

    /** Terms are merged by name: the reset of one database must not delete what another one still uses. */
    @ParameterizedTest
    @MethodSource("stores")
    void resetOfOneDatabaseKeepsSharedTerms(DataSource ds) throws Exception {
        StoreJdbc db = migrate(ds);
        CatalogRepository catalog = new CatalogRepository(db);
        SemanticRepository semantic = new SemanticRepository(db);
        Instant now = Instant.parse("2026-06-30T00:00:00Z");
        String suffix = String.valueOf(System.nanoTime());
        String[] tableIds = new String[2];
        String[] dbIds = new String[2];
        for (int i = 0; i < 2; i++) {
            dbIds[i] = catalog.upsertDatabase("shared_" + i + "_" + suffix, "h2", null, now);
            String schemaId = catalog.upsertSchema(dbIds[i], "SALES");
            tableIds[i] = catalog.upsertTable(schemaId, "CUSTOMERS", "base table", List.of("ID"), null);
        }
        String ownTable = catalog.upsertTable(catalog.upsertSchema(dbIds[0], "SALES"), "INVOICES", "base table",
                List.of("ID"), null);
        String shared = semantic.mergeTerm("Customer " + suffix, "A customer", List.of(), "semantic", now);
        String own = semantic.mergeTerm("Invoice " + suffix, "An invoice", List.of(), "semantic", now);
        semantic.linkTableTerm(tableIds[0], shared);
        semantic.linkTableTerm(tableIds[1], shared);
        semantic.linkTableTerm(ownTable, own);
        String attr0 = semantic.mergeColumnAttribute("Id", "", "INTEGER", "ID", "Customer", shared, tableIds[0],
                catalog.upsertColumn(tableIds[0], "ID", "INTEGER", false, 1, null), now);
        String attr1 = semantic.mergeColumnAttribute("Id", "", "INTEGER", "ID", "Customer", shared, tableIds[1],
                catalog.upsertColumn(tableIds[1], "ID", "INTEGER", false, 1, null), now);
        catalog.markJunction(ownTable);

        assertThat(semantic.deleteSemanticLayer(dbIds[0])).containsExactly(own);

        assertThat(semantic.termById(own)).isEmpty();
        assertThat(semantic.termById(shared)).isPresent();
        assertThat(semantic.tablesOfTerm(shared)).extracting(t -> t.get("id")).containsExactly(tableIds[1]);
        assertThat(semantic.attributeById(attr0)).isEmpty();
        assertThat(semantic.attributeById(attr1)).isPresent();
        assertThat(catalog.tablesWithoutTerm(dbIds[0])).hasSize(2);
        assertThat(catalog.tableById(ownTable).orElseThrow().get("is_junction_table")).isEqualTo(Boolean.FALSE);
        assertThat(semantic.attributeIdsOf(List.of(tableIds[1]), List.of())).containsExactly(attr1);
    }

    @ParameterizedTest
    @MethodSource("stores")
    void appTablesAndSettings(DataSource ds) throws Exception {
        StoreJdbc db = migrate(ds);
        AppRepository app = new AppRepository(db);
        Instant now = Instant.parse("2026-06-30T00:00:00Z");
        String conv = java.util.UUID.randomUUID().toString();
        app.createConversation(conv, "local", "Test", now);
        app.addMessage(conv, "user", "How many?", null, null, now);
        assertThat(app.messages(conv)).hasSize(1);
        RuntimeSettings settings = new RuntimeSettings(app, new de.jentsch.autoontology.common.AoTime(
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)));
        assertThat(settings.sqlQueryTimeoutSeconds()).isEqualTo(30);
        settings.set(RuntimeSettings.SQL_TIMEOUT, 99999);
        assertThat(settings.sqlQueryTimeoutSeconds()).isEqualTo(30);
        settings.set(RuntimeSettings.SQL_TIMEOUT, 12);
        assertThat(settings.sqlQueryTimeoutSeconds()).isEqualTo(12);
        GovernanceRepository gov = new GovernanceRepository(db);
        String tag = gov.addTag("PII-" + System.nanoTime(), "local", now);
        assertThat(gov.tag(tag)).isPresent();
    }
}
