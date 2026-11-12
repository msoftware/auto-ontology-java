// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.Json;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LlmGateway} on LangChain4j's OpenAI-compatible chat model (FR-LLM-02). The structured
 * output mode is configured per role ({@code json_schema}, {@code json_object},
 * {@code tool_calling}, {@code tool_calling_no_choice} for Claude behind gateways, FR-LLM-06)
 * instead of the model-name prefix trick of the original.
 */
public class LangChain4jLlmGateway implements LlmGateway {
    private static final Logger log = LoggerFactory.getLogger(LangChain4jLlmGateway.class);

    private final Map<ModelRole, ModelSettings> settings = new EnumMap<>(ModelRole.class);
    private final Map<ModelRole, ChatModel> models = new EnumMap<>(ModelRole.class);
    private final Semaphore inflight;
    private final int maxRetries;
    private final LlmMetrics metrics;

    public LangChain4jLlmGateway(AoProperties props, LlmMetrics metrics) {
        this.inflight = new Semaphore(Math.max(1, props.llm().maxInflight()));
        this.maxRetries = Math.max(1, props.llm().maxRetries());
        this.metrics = metrics;
        Duration timeout = Duration.ofSeconds(props.llm().invokeTimeoutS());
        for (ModelRole role : List.of(ModelRole.REASONING, ModelRole.NON_REASONING)) {
            ModelSettings s = ModelSettings.resolve(props.models(), role);
            settings.put(role, s);
            if (s.usable()) {
                models.put(role, OpenAiChatModel.builder()
                        .baseUrl(s.endpoint())
                        // empty keys are allowed for local servers; LangChain4j needs a value
                        .apiKey(s.hasKey() ? s.apiKey() : "none")
                        .modelName(s.model())
                        .temperature(0.0)
                        .timeout(timeout)
                        .maxRetries(0)
                        .strictJsonSchema(true)
                        .build());
                log.info("LLM role {}: {}", role, s);
            } else {
                log.warn("LLM role {} is not configured (endpoint/model missing)", role);
            }
        }
    }

    @Override
    public boolean configured() {
        return !models.isEmpty();
    }

    @Override
    public <T> T structured(ModelRole role, List<LlmMessage> messages, Class<T> type) {
        try {
            return attempt(role, messages, type);
        } catch (LlmException e) {
            if (role == ModelRole.NON_REASONING && models.containsKey(ModelRole.REASONING)) {
                log.warn("Non-reasoning structured call for {} failed; falling back to reasoning", type.getSimpleName());
                return attempt(ModelRole.REASONING, messages, type);
            }
            throw e;
        }
    }

    private <T> T attempt(ModelRole role, List<LlmMessage> messages, Class<T> type) {
        ChatModel model = model(role);
        String mode = settings.get(effective(role)).structuredOutput().toLowerCase(Locale.ROOT);
        RuntimeException last = null;
        for (int i = 0; i < maxRetries; i++) {
            long start = System.nanoTime();
            try {
                T value = call(model, mode, messages, type);
                metrics.record(role, type.getSimpleName(), System.nanoTime() - start, true);
                return value;
            } catch (RuntimeException e) {
                // tools.jackson JsonReadException / IllegalArgumentException / HTTP errors
                metrics.record(role, type.getSimpleName(), System.nanoTime() - start, false);
                last = e;
                log.warn("Structured LLM call {} attempt {}/{} failed: {}", type.getSimpleName(), i + 1, maxRetries,
                        e.getMessage());
                if (i < maxRetries - 1) {
                    // no pause after the last attempt: the caller is waiting for the failure
                    backoff(i);
                }
            }
        }
        throw new LlmException("Structured LLM call " + type.getSimpleName() + " failed", last);
    }

    private <T> T call(ChatModel model, String mode, List<LlmMessage> messages, Class<T> type) {
        List<ChatMessage> msgs = convert(messages);
        ChatRequest.Builder req = ChatRequest.builder();
        String raw;
        switch (mode) {
            case "tool_calling", "tool_calling_no_choice" -> {
                ToolSpecification tool = ToolSpecification.builder()
                        .name(type.getSimpleName())
                        .description("Return the structured result.")
                        .parameters(OutputSchema.langchain(type))
                        .build();
                req.messages(msgs).toolSpecifications(tool);
                if (mode.equals("tool_calling")) {
                    req.toolChoice(ToolChoice.REQUIRED);
                }
                ChatResponse resp = withPermit(() -> model.chat(req.build()));
                AiMessage ai = resp.aiMessage();
                List<ToolExecutionRequest> calls = ai.toolExecutionRequests();
                raw = calls == null || calls.isEmpty() ? ai.text() : calls.get(0).arguments();
            }
            case "json_object" -> {
                List<ChatMessage> withSchema = new ArrayList<>(msgs);
                withSchema.add(UserMessage.from("Respond ONLY with a JSON object that matches this JSON schema "
                        + "(fill the fields in the given order):\n" + Json.write(OutputSchema.jsonSchema(type))));
                req.messages(withSchema).responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build());
                raw = withPermit(() -> model.chat(req.build())).aiMessage().text();
            }
            default -> {
                JsonSchema schema = JsonSchema.builder().name(type.getSimpleName())
                        .rootElement(OutputSchema.langchain(type)).build();
                req.messages(msgs).responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON)
                        .jsonSchema(schema).build());
                raw = withPermit(() -> model.chat(req.build())).aiMessage().text();
            }
        }
        return StructuredParser.parse(raw, type);
    }

    @Override
    public String text(ModelRole role, List<LlmMessage> messages) {
        ChatModel model = model(role);
        RuntimeException last = null;
        for (int i = 0; i < maxRetries; i++) {
            long start = System.nanoTime();
            try {
                String out = withPermit(() -> model.chat(ChatRequest.builder().messages(convert(messages)).build()))
                        .aiMessage().text();
                metrics.record(role, "text", System.nanoTime() - start, true);
                return out;
            } catch (RuntimeException e) {
                metrics.record(role, "text", System.nanoTime() - start, false);
                last = e;
                log.warn("LLM text call attempt {}/{} failed: {}", i + 1, maxRetries, e.getMessage());
                if (i < maxRetries - 1) {
                    backoff(i);
                }
            }
        }
        throw new LlmException("LLM text call failed", last);
    }

    private ModelRole effective(ModelRole role) {
        if (models.containsKey(role)) {
            return role;
        }
        return role == ModelRole.NON_REASONING ? ModelRole.REASONING : ModelRole.NON_REASONING;
    }

    private ChatModel model(ModelRole role) {
        ChatModel m = models.get(effective(role));
        if (m == null) {
            throw new LlmException("No LLM endpoint configured (ao.models.* / DEFAULT_MODELS_*)");
        }
        return m;
    }

    private <R> R withPermit(java.util.function.Supplier<R> call) {
        try {
            inflight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while waiting for an LLM slot", e);
        }
        try {
            return call.get();
        } finally {
            inflight.release();
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(8000L, 500L * (1L << attempt)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted during LLM retry backoff", e);
        }
    }

    private static List<ChatMessage> convert(List<LlmMessage> messages) {
        List<ChatMessage> out = new ArrayList<>();
        for (LlmMessage m : messages) {
            out.add(switch (m.role()) {
                case SYSTEM -> SystemMessage.from(m.content());
                case USER -> UserMessage.from(m.content());
                case ASSISTANT -> AiMessage.from(m.content());
            });
        }
        return out;
    }
}
