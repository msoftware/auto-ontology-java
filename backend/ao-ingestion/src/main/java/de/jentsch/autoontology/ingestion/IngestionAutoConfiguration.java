// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.llm.Embedder;
import de.jentsch.autoontology.llm.LlmAutoConfiguration;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.GovernanceRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.store.StoreJdbc;
import de.jentsch.autoontology.vector.VectorAutoConfiguration;
import de.jentsch.autoontology.vector.VectorStore;
import java.time.Duration;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Ingestion beans (section 6) and governance services used by ingest and API. */
@AutoConfiguration(after = {VectorAutoConfiguration.class, LlmAutoConfiguration.class})
public class IngestionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConnectionService aoConnectionService(AoProperties props, CatalogRepository catalog, BeanFactory beans,
            AoTime time) {
        return new ConnectionService(props, catalog, beans, time);
    }

    @Bean
    @ConditionalOnMissingBean
    public CatalogWriter aoCatalogWriter(CatalogRepository catalog, SemanticRepository semantic,
            PlatformTransactionManager tm) {
        return new CatalogWriter(catalog, semantic, new TransactionTemplate(tm));
    }

    @Bean
    @ConditionalOnMissingBean
    public DataLayerEmbedder aoDataLayerEmbedder(CatalogRepository catalog, Embedder embedder, VectorStore vectors) {
        return new DataLayerEmbedder(catalog, embedder, vectors);
    }

    @Bean
    @ConditionalOnMissingBean
    public SearchService aoSearchService(StoreJdbc db, GovernanceRepository governance) {
        return new SearchService(db, governance);
    }

    @Bean
    @ConditionalOnMissingBean
    public RuleService aoRuleService(GovernanceRepository governance, SearchService search, CurrentUser user,
            AoTime time) {
        return new RuleService(governance, search, user, time);
    }

    @Bean
    @ConditionalOnMissingBean
    public PiiDetection aoPiiDetection(CatalogRepository catalog, SemanticRepository semantic,
            GovernanceRepository governance, RuntimeSettings settings, CurrentUser user, AoTime time, StoreJdbc db) {
        return new PiiDetection(catalog, semantic, governance, settings, user, time, db);
    }

    @Bean
    @ConditionalOnMissingBean
    public IngestService aoIngestService(ConnectionService connections, CatalogWriter writer, CatalogRepository catalog,
            DataLayerEmbedder embedder, VectorStore vectors, RuntimeSettings settings, AoTime time, PiiDetection pii,
            RuleService rules, SemanticRepository semantic) {
        IngestService ingest = new IngestService(connections, writer, catalog, semantic, embedder, vectors, settings,
                time);
        ingest.addStage(pii);
        // FR-ING-01 step 8: with the compilation disabled, rules are replayed after the ingest
        ingest.addStage(new IngestService.Stage() {
            @Override
            public void afterIngest(String databaseId, String databaseName) {
                if (!settings.semanticCompilationEnabled()) {
                    rules.replayAll();
                }
            }
        });
        return ingest;
    }

    @Bean
    @ConditionalOnMissingBean
    public IngestScheduler aoIngestScheduler(IngestService ingest, AoProperties props) {
        return new IngestScheduler(ingest, Duration.ofHours(Math.max(1, props.ingest().intervalHours())),
                props.ingest().schedulerEnabled());
    }
}
