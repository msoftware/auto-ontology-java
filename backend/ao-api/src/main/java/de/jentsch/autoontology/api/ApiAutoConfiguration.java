// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.api;

import de.jentsch.autoontology.agent.AgentAutoConfiguration;
import de.jentsch.autoontology.agent.AgentRunner;
import de.jentsch.autoontology.agent.ChartAdvisor;
import de.jentsch.autoontology.api.chat.ChatController;
import de.jentsch.autoontology.api.chat.ChatService;
import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.ingestion.ConnectionService;
import de.jentsch.autoontology.store.AppRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** REST API of Auto-Ontology under {@code ao.api-prefix} (section 11) plus the internal worker endpoints. */
@AutoConfiguration(after = AgentAutoConfiguration.class)
@Import({ChatController.class, AppController.class, CatalogController.class, SemanticController.class,
    GovernanceController.class, ExplorationController.class, ModelInterchangeController.class,
    InternalController.class})
public class ApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatService aoChatService(AgentRunner agent, ChartAdvisor charts, ConnectionService connections,
            AppRepository app, RuntimeSettings settings, CurrentUser user, AoTime time) {
        return new ChatService(agent, charts, connections, app, settings, user, time);
    }

    /** FR-API-03: responses are never cached ({@code Cache-Control: private, no-store}). */
    @Bean
    public FilterRegistrationBean<Filter> aoNoStoreFilter(AoProperties props) {
        Filter f = (req, res, chain) -> {
            if (res instanceof jakarta.servlet.http.HttpServletResponse http
                    && !"text/event-stream".equals(((jakarta.servlet.http.HttpServletRequest) req).getHeader("Accept"))) {
                http.setHeader("Cache-Control", "private, no-store");
            }
            chain.doFilter(req, res);
        };
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(f);
        reg.addUrlPatterns(props.apiPrefix() + "/*", props.resolvedNlChartPrefix() + "/*");
        reg.setName("aoNoStoreFilter");
        return reg;
    }
}
