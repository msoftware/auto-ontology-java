// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.semantic;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.ingestion.ConnectionService;
import de.jentsch.autoontology.ingestion.IngestScheduler;
import de.jentsch.autoontology.ingestion.IngestionAutoConfiguration;
import de.jentsch.autoontology.ingestion.PiiDetection;
import de.jentsch.autoontology.ingestion.RuleService;
import de.jentsch.autoontology.llm.Embedder;
import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.llm.RerankClient;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.HistoryRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.vector.VectorStore;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Semantic compilation beans (section 7). */
@AutoConfiguration(after = IngestionAutoConfiguration.class)
public class SemanticAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SemanticEmbedder aoSemanticEmbedder(Embedder embedder, VectorStore vectors, SemanticRepository semantic) {
        return new SemanticEmbedder(embedder, vectors, semantic);
    }

    @Bean
    @ConditionalOnMissingBean
    public SemanticCompiler aoSemanticCompiler(CatalogRepository catalog, SemanticRepository semantic, LlmGateway llm,
            Embedder embedder, VectorStore vectors, SemanticEmbedder semanticEmbedder, RerankClient rerank,
            RuntimeSettings settings, AoProperties props, AoTime time, PiiDetection pii) {
        return new SemanticCompiler(catalog, semantic, llm, embedder, vectors, semanticEmbedder, rerank, settings, props,
                time, pii);
    }

    @Bean
    @ConditionalOnMissingBean
    public SemanticCompilationService aoSemanticCompilationService(SemanticCompiler compiler,
            ConnectionService connections, CatalogRepository catalog, SemanticRepository semantic,
            HistoryRepository history, RuntimeSettings settings, VectorStore vectors, RuleService rules,
            PiiDetection pii, IngestScheduler ingestScheduler, AoTime time, AoProperties props) {
        return new SemanticCompilationService(compiler, connections, catalog, semantic, history, settings, vectors, rules,
                pii, ingestScheduler, time, Duration.ofHours(Math.max(1, props.semantic().intervalHours())),
                props.semantic().schedulerEnabled());
    }
}
