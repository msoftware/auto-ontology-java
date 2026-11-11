// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** FR-MCP-01/02: Streamable HTTP at /mcp, read-only tools over the public API, no authentication. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:mcp_meta;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "ao.vector.store=memory",
    "ao.vector.dimensions=64",
    "ao.embedding.provider=hash",
    "ao.ingest.scheduler-enabled=false",
    "ao.semantic.scheduler-enabled=false"})
class McpServerTest {
    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> rpc(String body, String session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            b.header("Mcp-Session-Id", session);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void listsAndCallsTools() throws Exception {
        HttpResponse<String> init = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
                null);
        assertThat(init.statusCode()).isEqualTo(200);
        assertThat(init.body()).contains("auto-ontology").contains("search_terms");
        String session = init.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertThat(rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session).statusCode())
                .isBetween(200, 299);
        HttpResponse<String> list = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session);
        assertThat(list.body()).contains("check_readiness", "search_terms", "get_term", "get_term_columns",
                "get_term_sql_attributes", "get_sql_attribute", "describe_table", "check_answerable", "ask_question");
        HttpResponse<String> call = rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"check_readiness\",\"arguments\":{}}}", session);
        // nothing ingested in this test: not ready, with blockers
        assertThat(call.body()).contains("ready").contains("blockers").contains("\\\"ready\\\":false");
    }
}
