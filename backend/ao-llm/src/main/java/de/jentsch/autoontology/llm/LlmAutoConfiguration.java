// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.CommonAutoConfiguration;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Model roles from {@code ao.models.*} without LangChain4j starters (NFR-QD-01). */
@AutoConfiguration(after = CommonAutoConfiguration.class)
public class LlmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LlmMetrics aoLlmMetrics() {
        return new LlmMetrics();
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmGateway aoLlmGateway(AoProperties props, LlmMetrics metrics) {
        return new LangChain4jLlmGateway(props, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public Embedder aoEmbedder(AoProperties props) {
        if ("hash".equalsIgnoreCase(props.embedding().provider())) {
            return new HashEmbedder(props.vector().dimensions());
        }
        return new OpenAiCompatibleEmbedder(ModelSettings.resolve(props.models(), ModelRole.EMBED), props.embedding(),
                props.vector().dimensions(), Duration.ofSeconds(props.llm().invokeTimeoutS()));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "ao.log-configuration", havingValue = "true", matchIfMissing = true)
    public ConfigurationReport aoConfigurationReport(AoProperties props, Environment env,
            ObjectProvider<LlmGateway> gateway, ObjectProvider<Embedder> embedder) {
        return new ConfigurationReport(props, env, gateway, embedder);
    }

    @Bean
    @ConditionalOnMissingBean
    public RerankClient aoRerankClient(AoProperties props) {
        return new RerankClient(ModelSettings.resolve(props.models(), ModelRole.RERANK),
                Duration.ofSeconds(props.llm().invokeTimeoutS()));
    }
}
