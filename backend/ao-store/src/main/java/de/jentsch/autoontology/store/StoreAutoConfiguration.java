// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.store;

import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CommonAutoConfiguration;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Metadata store on the host's primary {@link DataSource}. The schema is created by an own
 * Liquibase bean ({@code aoLiquibase}) so that it coexists with the host's migrations.
 */
@AutoConfiguration(
        after = {DataSourceAutoConfiguration.class, CommonAutoConfiguration.class},
        before = LiquibaseAutoConfiguration.class)
public class StoreAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "ao.store.liquibase.enabled", havingValue = "true", matchIfMissing = true)
    public SpringLiquibase aoLiquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new AoSpringLiquibase();
        liquibase.setDataSource(dataSource);
        return liquibase;
    }

    @Bean
    @ConditionalOnMissingBean
    public StoreJdbc aoStoreJdbc(DataSource dataSource, java.util.List<SpringLiquibase> migrations) {
        // the migrations parameter makes sure the schema exists before the first repository call
        return new StoreJdbc(JdbcClient.create(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    public CatalogRepository aoCatalogRepository(StoreJdbc db) {
        return new CatalogRepository(db);
    }

    @Bean
    @ConditionalOnMissingBean
    public SemanticRepository aoSemanticRepository(StoreJdbc db) {
        return new SemanticRepository(db);
    }

    @Bean
    @ConditionalOnMissingBean
    public AppRepository aoAppRepository(StoreJdbc db) {
        return new AppRepository(db);
    }

    @Bean
    @ConditionalOnMissingBean
    public HistoryRepository aoHistoryRepository(StoreJdbc db) {
        return new HistoryRepository(db);
    }

    @Bean
    @ConditionalOnMissingBean
    public GovernanceRepository aoGovernanceRepository(StoreJdbc db) {
        return new GovernanceRepository(db);
    }

    @Bean
    @ConditionalOnMissingBean
    public RuntimeSettings aoRuntimeSettings(AppRepository app, AoTime time) {
        return new RuntimeSettings(app, time);
    }
}
