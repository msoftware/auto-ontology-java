// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.jentsch.autoontology.common.ApiPrefixValidator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** NFR-TST-07: with ao.api-prefix=/ao every endpoint answers under /ao/..., /api/... is 404; invalid prefixes fail. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:prefix_meta;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "ao.api-prefix=/ao",
    "ao.vector.store=memory",
    "ao.vector.dimensions=64",
    "ao.embedding.provider=hash",
    "ao.ingest.scheduler-enabled=false",
    "ao.semantic.scheduler-enabled=false"})
class CustomApiPrefixTest {
    @LocalServerPort
    int port;

    private int status(String method, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        b = "POST".equals(method) ? b.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"search_term\":\"cu\"}")) : b.GET();
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void endpointsLiveUnderTheConfiguredPrefixOnly() throws Exception {
        assertThat(status("GET", "/ao/health")).isEqualTo(200);
        assertThat(status("GET", "/ao/terms")).isEqualTo(200);
        assertThat(status("GET", "/ao/conversations")).isEqualTo(200);
        assertThat(status("POST", "/ao/search/global-search")).isEqualTo(200);
        assertThat(status("GET", "/api/health")).isEqualTo(404);
        assertThat(status("GET", "/api/terms")).isEqualTo(404);
        // internal and health paths are fixed
        assertThat(status("GET", "/health/live")).isEqualTo(200);
    }

    @Test
    void invalidPrefixesAreRejected() {
        assertThatThrownBy(() -> ApiPrefixValidator.validate("ao.api-prefix", "ao/", null)).hasMessageContaining("start");
        assertThatThrownBy(() -> ApiPrefixValidator.validate("ao.api-prefix", "/ao/", null)).hasMessageContaining("end");
        assertThatThrownBy(() -> ApiPrefixValidator.validate("ao.api-prefix", "", null)).hasMessageContaining("empty");
        assertThatThrownBy(() -> ApiPrefixValidator.validate("ao.api-prefix", "/api/v1", "/api/v1"))
                .hasMessageContaining("collides");
        assertThatThrownBy(() -> ApiPrefixValidator.validate("ao.api-prefix", "/mcp", null))
                .hasMessageContaining("reserved");
    }
}
