// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.store.AppRepository;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.HistoryRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.store.StoreJdbc;
import de.jentsch.autoontology.vector.InMemoryVectorStore;
import de.jentsch.autoontology.vector.VectorStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import liquibase.integration.spring.SpringLiquibase;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;

/** FR-SEM-08: the reset deletes the semantic layer of all or of exactly one database. */
class SemanticResetTest {
    private static final Instant NOW = Instant.parse("2026-06-30T00:00:00Z");

    private CatalogRepository catalog;
    private SemanticRepository semantic;
    private VectorStore vectors;
    private SemanticCompilationService service;
    private String termId;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource store = new JdbcDataSource();
        store.setURL("jdbc:h2:mem:reset" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(store);
        lb.setChangeLog("classpath:db/changelog/ao-changelog.yaml");
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.afterPropertiesSet();
        StoreJdbc jdbc = new StoreJdbc(JdbcClient.create(store));
        catalog = new CatalogRepository(jdbc);
        semantic = new SemanticRepository(jdbc);
        AoTime time = new AoTime(Clock.fixed(NOW, ZoneOffset.UTC));
        vectors = new InMemoryVectorStore("", 4, null);
        vectors.ensureCollections();
        // the compilation flag is off (default): the trigger after a reset runs no pass, so no connector is needed
        service = new SemanticCompilationService(null, null, catalog, semantic, new HistoryRepository(jdbc),
                new RuntimeSettings(new AppRepository(jdbc), time), vectors, null, null, null, time,
                Duration.ofHours(24), false);

        String dbId = catalog.upsertDatabase("sales", "h2", null, NOW);
        String tableId = catalog.upsertTable(catalog.upsertSchema(dbId, "PUBLIC"), "CUSTOMERS", "base table",
                List.of("ID"), null);
        termId = semantic.mergeTerm("Customer", "A customer", List.of(), "semantic", NOW);
        semantic.linkTableTerm(tableId, termId);
        vectors.upsert(VectorStore.SEMANTIC_LAYER, List.of(new VectorStore.VectorRecord(termId, "Term: Customer.",
                "Term", "Customer", "sales", Map.of("name", "Customer"), new float[] {1, 0, 0, 0})));
    }

    /** A misspelt name used to fall through to "reset everything". */
    @Test
    void unknownDatabaseResetsNothing() {
        assertThat(service.reset("slaes")).isFalse();
        assertThat(semantic.termById(termId)).isPresent();
        assertThat(vectors.count(VectorStore.SEMANTIC_LAYER)).isEqualTo(1);
    }

    @Test
    void knownDatabaseIsReset() {
        assertThat(service.reset("sales")).isTrue();
        assertThat(semantic.termById(termId)).isEmpty();
        assertThat(vectors.count(VectorStore.SEMANTIC_LAYER)).isZero();
        assertThat(service.isCompiling()).isFalse();
    }

    @Test
    void withoutNameEveryDatabaseIsReset() {
        assertThat(service.reset(null)).isTrue();
        assertThat(semantic.countTerms()).isZero();
        assertThat(vectors.count(VectorStore.SEMANTIC_LAYER)).isZero();
    }
}
