// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.ingestion.PiiDetection;
import de.jentsch.autoontology.llm.Embedder;
import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.semantic.SemanticAutoConfiguration;
import de.jentsch.autoontology.store.AppRepository;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.vector.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Agent beans (section 9). */
@AutoConfiguration(after = SemanticAutoConfiguration.class)
public class AgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JoinPathFinder aoJoinPathFinder(CatalogRepository catalog, SemanticRepository semantic) {
        return new JoinPathFinder(catalog, semantic);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentServices aoAgentServices(LlmGateway llm, Embedder embedder, VectorStore vectors, CatalogRepository catalog,
            SemanticRepository semantic, JoinPathFinder joins, RuntimeSettings settings, AoProperties props, AoTime time,
            PiiDetection pii) {
        return new AgentServices(llm, embedder, vectors, catalog, semantic, joins, settings, props, time, pii);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentRunner aoAgentRunner(AgentServices services, AppRepository app) {
        return new AgentRunner(services, app);
    }

    @Bean
    @ConditionalOnMissingBean
    public EntityCoverageService aoEntityCoverageService(AgentServices services, AppRepository app,
            SemanticRepository semantic) {
        return new EntityCoverageService(services, app, semantic);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChartAdvisor aoChartAdvisor(LlmGateway llm) {
        return new ChartAdvisor(llm);
    }
}
