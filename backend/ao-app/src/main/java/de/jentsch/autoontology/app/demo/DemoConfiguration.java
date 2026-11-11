// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.app.demo;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.ingestion.IngestScheduler;
import de.jentsch.autoontology.store.RuntimeSettings;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo profile (FR-DEMO-01): creates the H2 source "demo_sales" before the schedulers start and
 * seeds the switches (compilation, visualization and PII detection on).
 */
@Configuration
@Profile("demo")
public class DemoConfiguration {

    /** Marker bean; created before lifecycle start, i.e. before the first ingest. */
    public record DemoReady() {}

    @Bean
    public DemoReady aoDemoReady(AoProperties props, RuntimeSettings settings) {
        if (props.ingest().connections() != null) {
            props.ingest().connections().stream()
                    .filter(c -> c.url() != null && c.url().startsWith("jdbc:h2:mem:demo_sales"))
                    .forEach(c -> DemoDatabase.create(c.url()));
        }
        settings.seed(RuntimeSettings.SEMANTIC_COMPILATION, true);
        settings.seed(RuntimeSettings.VISUALIZATION, true);
        settings.seed(RuntimeSettings.PII_DETECTION, true);
        return new DemoReady();
    }

    /** Demo questions shown as suggestions in the chat (FR-DEMO-04). */
    @RestController
    @RequestMapping("${ao.api-prefix:/api}/demo")
    @Profile("demo")
    static class DemoController {
        private final IngestScheduler scheduler;

        DemoController(IngestScheduler scheduler, DemoReady ready) {
            this.scheduler = scheduler;
        }

        @GetMapping("/questions")
        public Map<String, Object> questions() {
            return Map.of("data", List.of(
                    "How many orders were placed in June 2025?",
                    "Top 5 customers by revenue in 2025",
                    "Compare revenue of Retail vs. Wholesale customers in 2025",
                    "Revenue last quarter by state",
                    "How many wholesale customers are there?",
                    "Which suppliers deliver the stock item USB Cable (Grey) 2?",
                    "What was revenue for Water Bottle (Grey) 3 in Washington in June 2025?",
                    "Which tables contain customer data?",
                    "What was the return on ad spend in June 2025?"));
        }

        @org.springframework.web.bind.annotation.PostMapping("/reingest")
        public Map<String, Object> reingest() {
            scheduler.trigger();
            return Map.of("status", "triggered");
        }
    }
}
