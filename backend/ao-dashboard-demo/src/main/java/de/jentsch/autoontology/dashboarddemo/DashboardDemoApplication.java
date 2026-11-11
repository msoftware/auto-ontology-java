// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.dashboarddemo;

import de.jentsch.quasardashboard.metadata.ChartRepository;
import de.jentsch.quasardashboard.metadata.DashboardRepository;
import de.jentsch.quasardashboard.metadata.DatabaseRepository;
import de.jentsch.quasardashboard.metadata.DatasetRepository;
import de.jentsch.quasardashboard.metadata.MetadataStore;
import de.jentsch.quasardashboard.sampledata.SampleData;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Combined demo (FR-NLC-23): quasar-dashboard backend with its sample data plus Auto-Ontology and
 * NL charts in one host on H2, fixed "now" 2026-06-30. No security (NFR-QD-06).
 */
@SpringBootApplication
public class DashboardDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DashboardDemoApplication.class, args);
    }

    /** Marker: the fact tables and dashboard metadata exist before the first ingest runs. */
    public record SampleDataInstalled() {}

    @Bean
    SampleData sampleData(DataSource dataSource, MetadataStore store, DatabaseRepository databases,
            DatasetRepository datasets, ChartRepository charts, DashboardRepository dashboards) {
        return new SampleData(dataSource, store, databases, datasets, charts, dashboards);
    }

    /** Installed during context creation, i.e. before the ingest scheduler starts. */
    @Bean
    SampleDataInstalled sampleDataInstalled(SampleData sampleData,
            de.jentsch.autoontology.store.RuntimeSettings settings,
            java.util.List<de.jentsch.quasardashboard.metadata.MetadataSchemaInitializer> schema) {
        // the schema parameter makes sure the qd_* tables exist first
        sampleData.install();
        // the dataset sync attaches metrics to Terms, so the compilation is on in this demo
        settings.seed(de.jentsch.autoontology.store.RuntimeSettings.SEMANTIC_COMPILATION, true);
        settings.seed(de.jentsch.autoontology.store.RuntimeSettings.VISUALIZATION, true);
        return new SampleDataInstalled();
    }
}
