// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.connectors.ConnectionSpec;
import de.jentsch.autoontology.llm.HashEmbedder;
import de.jentsch.autoontology.store.AppRepository;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.GovernanceRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.store.StoreJdbc;
import de.jentsch.autoontology.vector.InMemoryVectorStore;
import de.jentsch.autoontology.vector.VectorStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import liquibase.integration.spring.SpringLiquibase;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** FR-ING-01..03 on H2: extract, diff, query history, PII, embeddings, delete. */
class IngestServiceTest {
    private String sourceUrl;
    private JdbcDataSource source;
    private CatalogRepository catalog;
    private IngestService ingest;
    private VectorStore vectors;
    private GovernanceRepository governance;
    private RuntimeSettings settings;

    @BeforeEach
    void setUp() throws Exception {
        sourceUrl = "jdbc:h2:mem:src" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        source = new JdbcDataSource();
        source.setURL(sourceUrl);
        JdbcClient src = JdbcClient.create(source);
        for (String s : List.of(
                "CREATE SCHEMA SALES",
                "CREATE SCHEMA DEMO_META",
                "CREATE TABLE SALES.CUSTOMERS (CUSTOMER_ID INT PRIMARY KEY, CUSTOMER_NAME VARCHAR(50), EMAIL VARCHAR(80))",
                "CREATE TABLE SALES.ORDERS (ORDER_ID INT PRIMARY KEY, CUSTOMER_ID INT REFERENCES SALES.CUSTOMERS(CUSTOMER_ID),"
                        + " ORDER_DATE DATE)",
                "CREATE TABLE DEMO_META.QUERY_HISTORY (END_TIME TIMESTAMP, QUERY_TEXT VARCHAR(4000))",
                "INSERT INTO DEMO_META.QUERY_HISTORY VALUES (CURRENT_TIMESTAMP, 'SELECT c.CUSTOMER_NAME, COUNT(*) FROM"
                        + " SALES.ORDERS o JOIN SALES.CUSTOMERS c ON o.CUSTOMER_ID = c.CUSTOMER_ID GROUP BY c.CUSTOMER_NAME')",
                "INSERT INTO DEMO_META.QUERY_HISTORY VALUES (CURRENT_TIMESTAMP, 'SELECT  c.CUSTOMER_NAME, COUNT(*) FROM"
                        + " SALES.ORDERS o JOIN SALES.CUSTOMERS c ON o.CUSTOMER_ID = c.CUSTOMER_ID GROUP BY c.CUSTOMER_NAME;')",
                "INSERT INTO DEMO_META.QUERY_HISTORY VALUES (CURRENT_TIMESTAMP, 'DELETE FROM SALES.ORDERS')")) {
            src.sql(s).update();
        }
        JdbcDataSource store = new JdbcDataSource();
        store.setURL("jdbc:h2:mem:st" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(store);
        lb.setChangeLog("classpath:db/changelog/ao-changelog.yaml");
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.afterPropertiesSet();
        StoreJdbc jdbc = new StoreJdbc(JdbcClient.create(store));
        catalog = new CatalogRepository(jdbc);
        governance = new GovernanceRepository(jdbc);
        AoTime time = new AoTime(Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC));
        settings = new RuntimeSettings(new AppRepository(jdbc), time);
        AoProperties props = new Binder(new MapConfigurationPropertySource(Map.of("ao.vector.dimensions", "64")))
                .bindOrCreate("ao", AoProperties.class);
        vectors = new InMemoryVectorStore("", 64, null);
        vectors.ensureCollections();
        ConnectionService connections = new ConnectionService(props, catalog, null, time);
        ingest = new IngestService(connections, new CatalogWriter(catalog,
                new TransactionTemplate(new DataSourceTransactionManager(store))), catalog,
                new DataLayerEmbedder(catalog, new HashEmbedder(64), vectors), vectors, settings, time);
        ingest.addStage(new PiiDetection(catalog, new SemanticRepository(jdbc), governance, settings,
                CurrentUser.fixed("local", "Local"), time, jdbc));
    }

    private ConnectionSpec spec() {
        return new ConnectionSpec("demo_sales", sourceUrl, null, null, null, List.of(), null, "^QUERY_HISTORY$");
    }

    @Test
    void ingestWritesCatalogHistoryAndEmbeddings() {
        settings.set(RuntimeSettings.PII_DETECTION, true);
        IngestService.Result r = ingest.run(spec());
        assertThat(r.tables()).isEqualTo(2);
        assertThat(r.queries()).isEqualTo(1);
        String dbId = r.databaseId();
        assertThat(catalog.tablesOfDatabase(dbId)).extracting(t -> t.get("name"))
                .containsExactlyInAnyOrder("CUSTOMERS", "ORDERS");
        assertThat(catalog.foreignKeys(dbId)).hasSize(1);
        assertThat(catalog.observedJoins(dbId)).hasSize(1);
        assertThat(catalog.sqlForTables(catalog.tablesOfDatabase(dbId).stream().map(t -> (String) t.get("id")).toList(), 5))
                .singleElement().satisfies(q -> assertThat(q.get("total_counter")).isEqualTo(2));
        // 2 tables + 6 columns
        assertThat(vectors.ids(VectorStore.DATA_LAYER, "demo_sales")).hasSize(8);
        String tag = (String) governance.tagByName("PII").orElseThrow().get("id");
        // EMAIL (email_address) and CUSTOMER_ID in a person table (identifier), as in pii_detection/rules.py
        assertThat(governance.tagTargets(tag, 0, 10)).hasSize(2);

        // second run: stable IDs, counters grow, removed column disappears
        JdbcClient.create(source).sql("ALTER TABLE SALES.CUSTOMERS DROP COLUMN EMAIL").update();
        IngestService.Result r2 = ingest.run(spec());
        assertThat(r2.databaseId()).isEqualTo(dbId);
        assertThat(vectors.ids(VectorStore.DATA_LAYER, "demo_sales")).hasSize(7);

        assertThat(ingest.deleteDatabase("demo_sales")).contains("demo_sales");
        assertThat(catalog.databaseByName("demo_sales")).isEmpty();
        assertThat(vectors.ids(VectorStore.DATA_LAYER, "demo_sales")).isEmpty();
    }

    @Test
    void textsFollowTheRetrievalContract() {
        assertThat(EmbeddingTexts.columnText("db", "s", "t", "c", "integer", "", List.of(1, 2)))
                .isEqualTo("database_name: db, schema_name: s, table_name: t, column_name: c, data_type: integer,"
                        + " sample_values: 1, 2");
        assertThat(EmbeddingTexts.tableText("db", "s", "t", "Desc", List.of(new EmbeddingTexts.ColumnText("a", "int", null),
                new EmbeddingTexts.ColumnText("b", "text", "B"))))
                .isEqualTo("database_name: db, schema_name: s, table_name: t, table_description: Desc,"
                        + " columns: {name: a, data_type: int},{name: b, data_type: text, description: B}");
    }

    @Test
    void piiRules() {
        assertThat(PiiDetection.evaluate("CUSTOMERS", "EMAIL")).isEqualTo("email_address");
        assertThat(PiiDetection.evaluate("customers", "customerName")).isNull();
        assertThat(PiiDetection.evaluate("people", "full_name")).isEqualTo("person_name");
        assertThat(PiiDetection.evaluate("products", "name")).isEqualTo("not_pii");
        assertThat(PiiDetection.evaluate("x", "created_at")).isEqualTo("not_pii");
    }

    @Test
    void parserFindsJoinsAndRejectsDml() {
        QueryHistoryParser.Parsed p = QueryHistoryParser.parse(
                "select a.x from s.t1 a join s.t2 b on a.id = b.t1_id where b.y = 1");
        assertThat(p.tables()).containsExactlyInAnyOrder("s.t1", "s.t2");
        assertThat(p.joins()).singleElement().satisfies(j -> assertThat(j.left().table()).isEqualTo("t1"));
        assertThat(QueryHistoryParser.parse("UPDATE t SET a = 1")).isNull();
        QueryHistoryParser.Parsed u = QueryHistoryParser.parse("SELECT a FROM t1 UNION SELECT b FROM t2");
        assertThat(u.unions()).hasSize(1);
    }

    @Test
    void incompleteExtractionFails() {
        assertThatThrownBy(() -> CatalogExtractor.extract(new de.jentsch.autoontology.connectors.H2Source("x", source, null) {
            @Override
            public List<Map<String, Object>> getColumns() {
                return List.of();
            }
        }, new de.jentsch.autoontology.connectors.TableFilter(List.of("SALES"), null, null)))
                .isInstanceOf(CatalogExtractor.IncompleteCatalogExtractionException.class);
    }
}
