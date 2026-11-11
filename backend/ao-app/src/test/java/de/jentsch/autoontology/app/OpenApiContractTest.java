// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.jentsch.autoontology.common.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * NFR-TST-03: the generated OpenAPI contains every path and method of
 * auto-ontology/docs/openapi/auto-ontology-api.json, compared relative to the prefix (the test
 * runs with ao.api-prefix=/ao). Deviations only via the documented allowlist (login endpoints
 * that are deliberately not built, section 1.2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:openapi_meta;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "ao.api-prefix=/ao",
    "ao.vector.store=memory",
    "ao.vector.dimensions=64",
    "ao.embedding.provider=hash",
    "ao.ingest.scheduler-enabled=false",
    "ao.semantic.scheduler-enabled=false"})
class OpenApiContractTest {
    static final Path REFERENCE = Path.of("../../../auto-ontology/docs/openapi/auto-ontology-api.json");

    /** Documented allowlist (NFR-TST-03): authentication and token endpoints of the original. */
    static final List<Pattern> ALLOWLIST = List.of(
            Pattern.compile("^/auth/.*"), Pattern.compile("^/sso-.*"), Pattern.compile("^/invitations.*"),
            Pattern.compile("^/api-tokens.*"), Pattern.compile(".*/sso-federation$"));

    @LocalServerPort
    int port;

    static Set<String> operations(Map<String, Object> doc, String prefix) {
        Set<String> out = new TreeSet<>();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) doc.get("paths");
        paths.forEach((path, ops) -> {
            if (!path.startsWith(prefix + "/")) {
                return;
            }
            String rel = path.substring(prefix.length()).replaceAll("\\{[^}]+}", "{}");
            for (String m : ops.keySet()) {
                if (Set.of("get", "post", "put", "patch", "delete").contains(m)) {
                    out.add(m.toUpperCase() + " " + rel);
                }
            }
        });
        return out;
    }

    @Test
    void generatedApiCoversTheReference() throws Exception {
        assumeTrue(Files.exists(REFERENCE), "reference OpenAPI of the original not found next to the project");
        Map<String, Object> reference = Json.readMap(Files.readString(REFERENCE));
        String body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/v3/api-docs")).build(), HttpResponse.BodyHandlers.ofString()).body();
        Set<String> ours = operations(Json.readMap(body), "/ao");
        Set<String> missing = new TreeSet<>(operations(reference, "/api"));
        missing.removeAll(ours);
        missing.removeIf(op -> ALLOWLIST.stream().anyMatch(p -> p.matcher(op.substring(op.indexOf(' ') + 1)).matches()));
        assertThat(missing).as("reference operations missing in the generated OpenAPI").isEmpty();
        assertThat(ours).isNotEmpty().allSatisfy(op -> assertThat(op).doesNotContain("/api/"));
    }
}
