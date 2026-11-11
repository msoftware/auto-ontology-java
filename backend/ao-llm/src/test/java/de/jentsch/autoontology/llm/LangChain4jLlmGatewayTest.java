// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * NFR-QD-01 smoke test: LangChain4j with Jackson 3 performs a structured-output call per mode
 * against a stubbed OpenAI-compatible endpoint, and no Jackson 2 databind is on the classpath.
 */
class LangChain4jLlmGatewayTest {
    /** Minimal output record. */
    record Answer(
            @LlmField("The answer") String answer,
            @LlmField(value = "Optional count", nullable = true) Integer count,
            List<String> items,
            Kind kind) implements LlmOutput {
        @Override
        public void validate() {
            if (answer.isBlank()) {
                throw new IllegalArgumentException("answer must not be blank");
            }
        }
    }

    /** Enum rendered lower-case. */
    enum Kind {
        INFORMATION,
        CALCULATION
    }

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile String content = "{\"answer\":\"42\",\"count\":null,\"items\":\"\",\"kind\":\"calculation\",\"$FUNCTION_NAME\":\"x\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            boolean tools = body.contains("\"tools\"");
            Map<String, Object> message = tools
                    ? Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "call_1",
                            "type", "function", "function", Map.of("name", "Answer", "arguments", content))))
                    : Map.of("role", "assistant", "content", content);
            String resp = Json.write(Map.of("id", "x", "object", "chat.completion", "created", 1, "model", "m",
                    "choices", List.of(Map.of("index", 0, "message", message,
                            "finish_reason", tools ? "tool_calls" : "stop")),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LangChain4jLlmGateway gateway(String mode) {
        Map<String, String> p = Map.of(
                "ao.models.defaults.endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "ao.models.defaults.model", "test-model",
                "ao.models.defaults.structured-output", mode,
                "ao.llm.max-retries", "2");
        AoProperties props = new Binder(new MapConfigurationPropertySource(p)).bindOrCreate("ao", AoProperties.class);
        return new LangChain4jLlmGateway(props, new LlmMetrics());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json_schema", "json_object", "tool_calling", "tool_calling_no_choice"})
    void structuredOutputPerMode(String mode) {
        Answer a = gateway(mode).structured(ModelRole.NON_REASONING,
                List.of(LlmMessage.system("sys"), LlmMessage.user("q")), Answer.class);
        assertThat(a.answer()).isEqualTo("42");
        assertThat(a.items()).isEmpty();
        assertThat(a.kind()).isEqualTo(Kind.CALCULATION);
        String body = bodies.get(bodies.size() - 1).replaceAll("\\s+", "");
        switch (mode) {
            case "json_schema" -> assertThat(body).contains("json_schema").contains("\"additionalProperties\":false");
            case "json_object" -> assertThat(body).contains("json_object").contains("JSONschema");
            case "tool_calling" -> assertThat(body).contains("\"tool_choice\":\"required\"");
            default -> assertThat(body).contains("\"tools\"").doesNotContain("tool_choice");
        }
    }

    @Test
    void unknownFieldsAndValidationErrorsFail() {
        content = "{\"answer\":\"\",\"count\":1,\"items\":[],\"kind\":\"information\"}";
        assertThatThrownBy(() -> gateway("json_schema").structured(ModelRole.REASONING, List.of(LlmMessage.user("q")),
                Answer.class)).isInstanceOf(LlmException.class);
        content = "{\"answer\":\"x\",\"count\":1,\"items\":[],\"kind\":\"information\",\"extra\":1}";
        assertThatThrownBy(() -> gateway("json_schema").structured(ModelRole.REASONING, List.of(LlmMessage.user("q")),
                Answer.class)).isInstanceOf(LlmException.class);
    }

    @Test
    void jackson2DatabindIsNotOnTheClasspath() {
        assertThatThrownBy(() -> Class.forName("com.fasterxml.jackson.databind.ObjectMapper"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void hashEmbedderIsDeterministicAndSimilarForSimilarText() {
        HashEmbedder e = new HashEmbedder(256);
        float[] a = e.embedQuery("customer name");
        float[] b = e.embedQuery("CUSTOMER_NAME of customers");
        float[] c = e.embedQuery("warehouse stock color");
        assertThat(a).containsExactly(e.embedQuery("customer name"));
        assertThat(dot(a, b)).isGreaterThan(dot(a, c));
    }

    private static double dot(float[] a, float[] b) {
        double d = 0;
        for (int i = 0; i < a.length; i++) {
            d += a[i] * b[i];
        }
        return d;
    }
}
