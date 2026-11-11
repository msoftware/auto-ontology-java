// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.dashboarddemo;

import static org.assertj.core.api.Assertions.assertThat;

import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.llm.ScriptedLlmGateway;
import de.jentsch.autoontology.nlchart.NlChartService.Alternative;
import de.jentsch.autoontology.nlchart.NlChartService.ChartSuggestion;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Combined demo (FR-NLC-23) with a scripted LLM: ingest of the dashboard analytics DB through the
 * DataSource bean, dataset sync, suggestion in mode A with a repair round (invalid column), filter
 * value repair ('germany' → 'Germany'), validation through the dashboard query engine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ao.vector.dimensions=256")
class NlChartIntegrationTest {
    static final AtomicInteger REGION_CALLS = new AtomicInteger();

    @TestConfiguration
    static class Llm {
        @Bean
        @Primary
        LlmGateway scripted() {
            return new ScriptedLlmGateway().on(ChartSuggestion.class, prompt -> {
                if (prompt.contains("Question: Umsatz in Deutschland nach Kanal")) {
                    return new ChartSuggestion("1", "bar", """
                            {"x_axis":"channel","metrics":["total_revenue"],
                             "adhoc_filters":[{"col":"country","op":"==","val":"germany"}]}""",
                            "Umsatz in Deutschland nach Kanal", "", "Umsatz je Kanal, gefiltert auf Deutschland",
                            List.of("Deutschland = Germany"), List.of());
                }
                // first answer uses a column that does not exist, the repair round fixes it
                return REGION_CALLS.incrementAndGet() == 1
                        ? new ChartSuggestion("1", "bar", "{\"x_axis\":\"regions\",\"metrics\":[\"total_revenue\"]}",
                                "Umsatz je Region", "", "Umsatz je Region", List.of(), List.of())
                        : new ChartSuggestion("1", "bar", "{\"x_axis\":\"region\",\"metrics\":[\"total_revenue\"]}",
                                "Umsatz je Region", "", "Umsatz je Region als Balken", List.of("Umsatz = total_revenue"),
                                List.of(new Alternative("pie", "Anteile je Region")));
            });
        }
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private String post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private List<Map<String, Object>> events(String sse) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : sse.split("\n")) {
            if (line.startsWith("data:") && !line.contains("[DONE]")) {
                out.add(Json.readMap(line.substring(5).strip()));
            }
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void datasetSyncSuggestionAndValidation() throws Exception {
        Map<String, Object> ds = Map.of();
        for (int i = 0; i < 120; i++) {
            ds = Json.readMap(get("/api/nl-chart/datasets"));
            List<Map<String, Object>> data = (List<Map<String, Object>>) ds.get("data");
            if (data.size() == 4 && data.stream().allMatch(d -> d.get("table_id") != null && !((List<Object>) d.get("terms"))
                    .isEmpty())) {
                break;
            }
            Thread.sleep(500);
            if (i % 10 == 9) {
                post("/api/nl-chart/sync", "{}");
            }
        }
        List<Map<String, Object>> data = (List<Map<String, Object>>) ds.get("data");
        assertThat(data).hasSize(4).allSatisfy(d -> assertThat(d.get("table_id")).isNotNull());
        // metrics of the datasets are mirrored as SQL attributes with source 'dataset'
        Map<String, Object> sqlAttrs = Json.readMap(get("/api/sql-attributes"));
        assertThat((List<Map<String, Object>>) sqlAttrs.get("data"))
                .anySatisfy(a -> assertThat(a.get("source")).isEqualTo("dataset"));

        List<Map<String, Object>> ev = events(post("/api/nl-chart/suggest",
                "{\"question\":\"Umsatz je Region als Balkendiagramm\"}"));
        Map<String, Object> s = ev.stream().filter(e -> "chart_suggestion".equals(e.get("type"))).findFirst().orElseThrow();
        assertThat(s).containsEntry("mode", "semantic").containsEntry("viz_type", "bar");
        assertThat(((Number) s.get("dataset_id")).intValue()).isEqualTo(1);
        assertThat((Map<String, Object>) s.get("params")).containsEntry("x_axis", "region");
        assertThat(s.get("query_context")).isNotNull();
        assertThat(ev).anySatisfy(e -> assertThat(e).containsEntry("node", "repair"));

        Map<String, Object> de = events(post("/api/nl-chart/suggest", "{\"question\":\"Umsatz in Deutschland nach Kanal\"}"))
                .stream().filter(e -> "chart_suggestion".equals(e.get("type"))).findFirst().orElseThrow();
        List<Map<String, Object>> filters = (List<Map<String, Object>>) ((Map<String, Object>) de.get("params"))
                .get("adhoc_filters");
        assertThat(filters.get(0)).containsEntry("val", "Germany");
        assertThat((List<String>) de.get("assumptions")).anySatisfy(a -> assertThat(a).contains("'germany' → 'Germany'"));

        Map<String, Object> invalid = Json.readMap(post("/api/nl-chart/validate",
                "{\"dataset_id\":1,\"viz_type\":\"line\",\"params\":{\"x_axis\":\"order_date\","
                        + "\"metrics\":[\"total_revenue\"],\"time_range\":\"next blue moon\"}}"));
        assertThat(invalid.get("valid")).isEqualTo(false);
        assertThat((List<String>) invalid.get("errors")).anySatisfy(e -> assertThat(e).contains("INVALID_TIME_RANGE"));
        Map<String, Object> valid = Json.readMap(post("/api/nl-chart/validate",
                "{\"dataset_id\":1,\"viz_type\":\"line\",\"params\":{\"x_axis\":\"order_date\","
                        + "\"metrics\":[\"total_revenue\"],\"time_grain_sqla\":\"P1M\",\"time_range\":\"Last year\"}}"));
        assertThat(valid.get("valid")).isEqualTo(true);

        // the dashboard API still answers under its own prefix in the same host
        assertThat(get("/api/v1/dataset/")).contains("sales");
    }
}
