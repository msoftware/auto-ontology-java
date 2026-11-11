// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.nlchart;

import de.jentsch.autoontology.agent.AgentRunner;
import de.jentsch.autoontology.agent.ChartAdvisor;
import de.jentsch.autoontology.api.ApiAutoConfiguration;
import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.ingestion.ConnectionService;
import de.jentsch.autoontology.ingestion.IngestService;
import de.jentsch.autoontology.llm.Embedder;
import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.semantic.SemanticCompilationService;
import de.jentsch.autoontology.semantic.SemanticEmbedder;
import de.jentsch.autoontology.store.AppRepository;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.store.StoreJdbc;
import de.jentsch.autoontology.vector.VectorStore;
import de.jentsch.quasardashboard.autoconfigure.QuasarDashboardAutoConfiguration;
import de.jentsch.quasardashboard.metadata.DatasetRepository;
import de.jentsch.quasardashboard.service.ChartDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * NL charts for quasar-dashboard (section 23); active only when the dashboard backend runs in the
 * same host. The dataset sync runs at start, after every ingest and compilation, and on trigger.
 */
@AutoConfiguration(after = {ApiAutoConfiguration.class, QuasarDashboardAutoConfiguration.class})
@ConditionalOnClass(DatasetRepository.class)
@ConditionalOnBean({DatasetRepository.class, ChartDataService.class})
@Import(NlChartController.class)
public class NlChartAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(NlChartAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public DatasetSync aoDatasetSync(DatasetRepository datasets, StoreJdbc db, CatalogRepository catalog,
            SemanticRepository semantic, SemanticEmbedder embedder, VectorStore vectors, AoProperties props,
            CurrentUser user, AoTime time, IngestService ingest, SemanticCompilationService compilation) {
        DatasetSync sync = new DatasetSync(datasets, db, catalog, semantic, embedder, vectors, props, user, time);
        ingest.addStage(new IngestService.Stage() {
            @Override
            public void afterIngest(String databaseId, String databaseName) {
                sync.sync();
            }
        });
        compilation.onRunCompleted(sync::sync);
        return sync;
    }

    @Bean
    @ConditionalOnMissingBean
    public ChartValidator aoChartValidator(ChartDataService chartData, AoTime time) {
        return new ChartValidator(chartData, time);
    }

    @Bean
    @ConditionalOnMissingBean
    public NlChartService aoNlChartService(DatasetSync sync, ChartValidator validator, LlmGateway llm, Embedder embedder,
            VectorStore vectors, AgentRunner agent, ChartAdvisor advisor, ConnectionService connections,
            AppRepository app, AoProperties props, CurrentUser user, AoTime time) {
        return new NlChartService(sync, validator, llm, embedder, vectors, agent, advisor, connections, app, props, user,
                time);
    }

    @Bean
    public ApplicationListener<ApplicationReadyEvent> aoDatasetSyncAtStart(DatasetSync sync) {
        return e -> {
            try {
                sync.sync();
            } catch (RuntimeException ex) {
                log.warn("Dataset sync at start failed: {}", ex.getMessage());
            }
        };
    }
}
