// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.api.chat;

import de.jentsch.autoontology.agent.AgentEvents;
import de.jentsch.autoontology.agent.AgentRunner;
import de.jentsch.autoontology.agent.ChartAdvisor;
import de.jentsch.autoontology.common.ApiException;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CancellationToken;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.common.Ids;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.ingestion.ConnectionService;
import de.jentsch.autoontology.store.AppRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat runtime (server/chat/router.py, conversation_dal.py; FR-CHAT-01..04): one active run per
 * conversation (409 while its client is connected, an orphaned run is displaced), runs on virtual
 * threads, events buffered for {@code watch} from the beginning, persistence in the backend even
 * after the client disconnected.
 */
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** One running agent run. */
    static final class Run {
        final String conversationId;
        final CancellationToken token = new CancellationToken();
        final List<Map<String, Object>> buffer = new CopyOnWriteArrayList<>();
        final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        volatile boolean clientConnected = true;
        volatile boolean done;

        Run(String conversationId) {
            this.conversationId = conversationId;
        }
    }

    /** A chat request (server/chat/helpers.py::ChatRequest). */
    public record ChatRequest(String question, String evidence, String conversationId, Boolean prediction,
            String targetDb) {}

    private final AgentRunner agent;
    private final ChartAdvisor charts;
    private final ConnectionService connections;
    private final AppRepository app;
    private final RuntimeSettings settings;
    private final CurrentUser user;
    private final AoTime time;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("ao-sse-ping").factory());

    public ChatService(AgentRunner agent, ChartAdvisor charts, ConnectionService connections, AppRepository app,
            RuntimeSettings settings, CurrentUser user, AoTime time) {
        this.agent = agent;
        this.charts = charts;
        this.connections = connections;
        this.app = app;
        this.settings = settings;
        this.user = user;
        this.time = time;
    }

    // ------------------------------------------------------------------ completions

    public SseEmitter completions(ChatRequest req, String source) {
        if (req.question() == null || req.question().isBlank()) {
            throw ApiException.unprocessable("question must contain at least 1 character");
        }
        String conv = req.conversationId();
        if (conv != null && !Ids.isUuid(conv)) {
            throw ApiException.unprocessable("conversation_id must be a UUID");
        }
        Run run = new Run(conv);
        if (conv != null) {
            // check and replace as one step: two requests for one conversation must not both start
            synchronized (runs) {
                Run existing = runs.get(conv);
                if (existing != null && !existing.done) {
                    if (existing.clientConnected) {
                        throw new ApiException(HttpStatus.CONFLICT, "Conversation in progress");
                    }
                    // an orphaned run is displaced by a new request
                    existing.token.cancel();
                }
                runs.put(conv, run);
            }
        }
        SseEmitter emitter = subscribe(run);
        Thread.ofVirtual().name("ao-chat-" + (conv == null ? "stateless" : conv)).start(() -> execute(run, req, source));
        return emitter;
    }

    private SseEmitter subscribe(Run run) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        attach(run, emitter);
        run.subscribers.add(emitter);
        return emitter;
    }

    /**
     * Life-cycle callbacks and keep-alive of one subscriber (the requesting client or a watcher).
     * The run counts as connected while at least one subscriber is left.
     */
    private void attach(Run run, SseEmitter emitter) {
        Runnable gone = () -> {
            run.subscribers.remove(emitter);
            run.clientConnected = !run.subscribers.isEmpty();
        };
        // SSE comments in idle phases keep proxies from closing the connection
        ScheduledFuture<?> ping = pinger.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment(" ping"));
            } catch (IOException | IllegalStateException e) {
                gone.run();
                throw new IllegalStateException("closed");
            }
        }, 15, 15, TimeUnit.SECONDS);
        // SseEmitter keeps exactly one completion callback: a second onCompletion() replaces the first
        emitter.onCompletion(() -> {
            ping.cancel(false);
            gone.run();
        });
        emitter.onTimeout(gone);
        emitter.onError(e -> gone.run());
    }

    /** Buffer and delivery under the run's monitor, so that {@link #watch} never misses an event. */
    private void emit(Run run, Map<String, Object> event) {
        String json = Json.write(event);
        synchronized (run) {
            run.buffer.add(event);
            for (SseEmitter e : run.subscribers) {
                try {
                    e.send(SseEmitter.event().data(" " + json, MediaType.TEXT_PLAIN));
                } catch (IOException | IllegalStateException ex) {
                    run.subscribers.remove(e);
                    run.clientConnected = !run.subscribers.isEmpty();
                }
            }
        }
    }

    private void finish(Run run) {
        synchronized (run) {
            run.done = true;
            for (SseEmitter e : run.subscribers) {
                try {
                    e.send(SseEmitter.event().data(" [DONE]", MediaType.TEXT_PLAIN));
                    e.complete();
                } catch (IOException | IllegalStateException ex) {
                    // client already gone
                }
            }
        }
        if (run.conversationId != null) {
            runs.remove(run.conversationId, run);
        }
    }

    private void execute(Run run, ChatRequest req, String source) {
        String analyticsId = null;
        CancellationToken.Scope scope = run.token.bind();
        try {
            List<Map<String, String>> history = List.of();
            String conv = run.conversationId;
            if (conv != null) {
                history = prepareConversation(conv, req.question());
            }
            analyticsId = app.addAnalytics(user.id(), conv, source, req.question(), time.now());
            List<SourceDatabase> connectors = connections.answeringConnectors();
            if (connectors.isEmpty()) {
                emit(run, AgentEvents.error("No database connection is configured.", null, "ConfigurationError", null));
                return;
            }
            Map<String, Object> answer = agent.run(new AgentRunner.Request(req.question(), req.evidence(), req.prediction(),
                    req.targetDb(), history, connectors), e -> emit(run, e), run.token);
            if (answer == null) {
                return;
            }
            persistAssistant(conv, analyticsId, answer);
            if (conv != null) {
                chartsStep(run, conv, req.question(), answer);
            }
        } catch (CancellationToken.CancelledException e) {
            emit(run, AgentEvents.error("The run was cancelled.", null, "Cancelled", null));
        } catch (RuntimeException e) {
            log.error("Chat run failed", e);
            emit(run, AgentEvents.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), null,
                    e.getClass().getSimpleName(), null));
        } finally {
            scope.close();
            finish(run);
        }
    }

    /** prepare_conversation: create when unknown, load history, store the user message, touch. */
    private List<Map<String, String>> prepareConversation(String conv, String question) {
        if (app.conversation(conv).isEmpty()) {
            app.createConversation(conv, user.id(), title(question), time.now());
        }
        List<Map<String, String>> history = completedRounds(app.messages(conv));
        app.addMessage(conv, "user", question, null, null, time.now());
        app.touchConversation(conv, time.now());
        return history;
    }

    static String title(String question) {
        String t = question.strip().replaceAll("\\s+", " ");
        return t.length() > 80 ? t.substring(0, 77) + "..." : t;
    }

    /** Question, answer text and SQL of completed rounds. */
    static List<Map<String, String>> completedRounds(List<Map<String, Object>> messages) {
        List<Map<String, String>> rounds = new ArrayList<>();
        Map<String, String> current = null;
        for (Map<String, Object> m : messages) {
            if ("user".equals(m.get("role"))) {
                current = new LinkedHashMap<>();
                current.put("question", String.valueOf(m.get("content")));
            } else if (current != null && "assistant".equals(m.get("role")) && !current.containsKey("answer")) {
                current.put("answer", m.get("content") == null ? "" : String.valueOf(m.get("content")));
                current.put("sql", m.get("sql_code") == null ? "" : String.valueOf(m.get("sql_code")));
                rounds.add(current);
            }
        }
        return rounds;
    }

    private void persistAssistant(String conv, String analyticsId, Map<String, Object> answer) {
        String text = String.valueOf(answer.getOrDefault("response", ""));
        String sql = answer.get("sql_code") == null ? null : String.valueOf(answer.get("sql_code"));
        if (conv != null) {
            app.addMessage(conv, "assistant", text, sql, null, time.now());
            app.touchConversation(conv, time.now());
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> thoughts = (List<Map<String, Object>>) answer.getOrDefault("thoughts", List.of());
        String reasoning = thoughts.stream().map(t -> t.get("label") + ": " + t.get("thought"))
                .reduce((a, b) -> a + "\n" + b).orElse(null);
        app.completeAnalytics(analyticsId, text, reasoning, sql, time.now());
    }

    @SuppressWarnings("unchecked")
    private void chartsStep(Run run, String conv, String question, Map<String, Object> answer) {
        Object rows = answer.get("sql_response_from_db");
        if (!(rows instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rowMaps = (List<Map<String, Object>>) rows;
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (settings.visualizationEnabled()) {
            try {
                charts.recommend(question, String.valueOf(answer.get("sql_code")),
                        (List<String>) answer.getOrDefault("sql_columns", List.of()),
                        (List<Integer>) answer.get("sql_column_types"), rowMaps).ifPresent(blocks::add);
            } catch (RuntimeException e) {
                log.warn("Chart recommendation failed: {}", e.getMessage());
            }
        }
        emit(run, AgentEvents.charts(blocks, rowMaps));
        // build_result_message: chart blocks, or the rows as JSON when there is no chart
        if (!blocks.isEmpty()) {
            StringBuilder content = new StringBuilder();
            for (Map<String, Object> b : blocks) {
                content.append("```chart\n").append(Json.write(b)).append("\n```\n");
            }
            // the rows are stored with the chart so that a reopened conversation can render it again
            app.addMessage(conv, "assistant", content.toString().strip(), null, Json.write(rowMaps), time.now());
        } else {
            app.addMessage(conv, "assistant", "", null, Json.write(rowMaps), time.now());
        }
    }

    // ------------------------------------------------------------------ watch / cancel

    /** Replays every event of the running run from the start, then live; ends at once when nothing runs. */
    public SseEmitter watch(String conversationId) {
        Run run = conversationId == null ? null : runs.get(conversationId);
        if (run == null || run.done) {
            SseEmitter e = new SseEmitter(1000L);
            try {
                e.send(SseEmitter.event().data(" [DONE]", MediaType.TEXT_PLAIN));
                e.complete();
            } catch (IOException ex) {
                e.completeWithError(ex);
            }
            return e;
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        // emit() and finish() take the same monitor: replay and subscription are one step
        synchronized (run) {
            try {
                for (Map<String, Object> ev : run.buffer) {
                    emitter.send(SseEmitter.event().data(" " + Json.write(ev), MediaType.TEXT_PLAIN));
                }
                if (run.done) {
                    // finished between the lookup and here: nobody would ever send [DONE] otherwise
                    emitter.send(SseEmitter.event().data(" [DONE]", MediaType.TEXT_PLAIN));
                    emitter.complete();
                    return emitter;
                }
            } catch (IOException | IllegalStateException ex) {
                return emitter;
            }
            attach(run, emitter);
            run.subscribers.add(emitter);
            run.clientConnected = true;
        }
        return emitter;
    }

    public Map<String, Object> cancel(String conversationId) {
        Run run = conversationId == null ? null : runs.get(conversationId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (run == null) {
            out.put("cancelled", false);
            return out;
        }
        run.token.cancel();
        out.put("cancelled", true);
        return out;
    }

    public Optional<Boolean> isRunning(String conversationId) {
        Run r = runs.get(conversationId);
        return r == null ? Optional.empty() : Optional.of(!r.done);
    }
}
