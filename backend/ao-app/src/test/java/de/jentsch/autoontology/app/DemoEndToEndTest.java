// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.app;

import static org.assertj.core.api.Assertions.assertThat;

import de.jentsch.autoontology.agent.AgentModels.IntentValidationModel;
import de.jentsch.autoontology.agent.AgentModels.SQLGenerationModel;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.llm.ScriptedLlmGateway;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * NFR-TST-04 with a scripted LLM (CI basis): the demo app ingests and compiles the H2 sales schema,
 * then a chat run repairs an invalid SQL (reconstruction visible in the trace) and corrects the
 * filter value 'wholesale' to the stored 'Wholesale' via check_value_repair.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:e2e_meta;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "ao.vector.persist=false",
    "ao.vector.dimensions=256"})
@ActiveProfiles("demo")
class DemoEndToEndTest {
    static final AtomicInteger GENERATIONS = new AtomicInteger();

    @TestConfiguration
    static class ScriptedLlm {
        @Bean
        @Primary
        LlmGateway scriptedLlm() {
            return new ScriptedLlmGateway()
                    .on(SQLGenerationModel.class, prompt -> GENERATIONS.incrementAndGet() == 1
                            // first attempt references a table that does not exist
                            ? new SQLGenerationModel("Count wholesale customers.",
                                    "SELECT c.CUSTOMER_ID FROM SALES.CLIENTS c WHERE c.CUSTOMER_CATEGORY = 'wholesale'",
                                    "Lists the wholesale customers.")
                            : new SQLGenerationModel("Use the customers table.",
                                    "SELECT c.CUSTOMER_ID FROM SALES.CUSTOMERS c WHERE c.CUSTOMER_CATEGORY = 'wholesale'",
                                    "Lists the wholesale customers."))
                    .on(IntentValidationModel.class, prompt -> new IntentValidationModel("ok", true, List.of(), List.of(),
                            List.of(), List.of()));
        }
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestCompileAndAnswerWithRepair() throws Exception {
        // wait until the compilation finished and all terms are written (slow under a parallel full build)
        Map<String, Object> terms = Map.of();
        for (int i = 0; i < 240; i++) {
            boolean calculated = Boolean.TRUE.equals(
                    Json.readMap(get("/api/semantic-compilation/status")).get("calculated"));
            terms = Json.readMap(get("/api/terms?limit=50"));
            if (calculated && ((Number) terms.get("total")).intValue() >= 12) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(((Number) terms.get("total")).intValue()).isEqualTo(12);
        Map<String, Object> dbs = Json.readMap(get("/api/datasources/dbs"));
        assertThat(dbs.get("count")).isEqualTo(1);

        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/chat/completions")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"How many wholesale customers are there?\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        // parsed like the Python example of docs/conversation-api.md
        List<Map<String, Object>> events = new ArrayList<>();
        boolean done = false;
        for (String line : resp.body().split("\n")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).strip();
            if (payload.equals("[DONE]")) {
                done = true;
                break;
            }
            events.add(Json.readMap(payload));
        }
        assertThat(done).isTrue();
        assertThat(events).anySatisfy(e -> assertThat(e).containsEntry("node", "reconstruct_sql"));
        Map<String, Object> result = events.stream().filter(e -> "result".equals(e.get("type"))).findFirst()
                .orElseThrow(() -> new AssertionError("no result event in " + events));
        Map<String, Object> answer = (Map<String, Object>) result.get("answer");
        assertThat((String) answer.get("sql_code")).contains("'Wholesale'");
        assertThat((List<Object>) answer.get("sql_response_from_db")).isNotEmpty();
        List<Map<String, Object>> thoughts = (List<Map<String, Object>>) answer.get("thoughts");
        assertThat(thoughts).anySatisfy(t -> assertThat(t.get("node")).isEqualTo("check_value_repair"));
        // the validated SQL was streamed as an sql event before execution
        assertThat(events).anySatisfy(e -> assertThat(e).containsEntry("type", "sql"));
        // FR-AUTH-01: analytics rows carry the fixed user
        Map<String, Object> analytics = Json.readMap(get("/api/analytics"));
        assertThat((List<Map<String, Object>>) analytics.get("data"))
                .allSatisfy(a -> assertThat(a.get("user_id")).isEqualTo("demo"));
    }
}
