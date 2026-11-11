// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.api;

import de.jentsch.autoontology.common.ApiException;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.common.Ids;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.semantic.SemanticCompilationService;
import de.jentsch.autoontology.store.AppRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes the original serves from Next.js (conversations, acronyms, custom prompts,
 * configurations, analytics); errors use {@code {"error": ...}} or plain text (FR-API-02).
 */
@RestController
@RequestMapping("${ao.api-prefix:/api}")
public class AppController {
    private final AppRepository app;
    private final RuntimeSettings settings;
    private final SemanticCompilationService compilation;
    private final CurrentUser user;
    private final AoTime time;

    public AppController(AppRepository app, RuntimeSettings settings, SemanticCompilationService compilation,
            CurrentUser user, AoTime time) {
        this.app = app;
        this.settings = settings;
        this.compilation = compilation;
        this.user = user;
        this.time = time;
    }

    // ------------------------------------------------------------------ conversations (FR-CHAT-05)

    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations() {
        return app.conversations();
    }

    @PostMapping("/conversations")
    public ResponseEntity<Map<String, Object>> createConversation(@RequestBody(required = false) Map<String, Object> body) {
        String id = Ids.newId();
        String title = body == null || body.get("title") == null ? "New conversation" : String.valueOf(body.get("title"));
        app.createConversation(id, user.id(), title, time.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(app.conversation(id).orElseThrow());
    }

    @GetMapping("/conversations/{id}")
    public Map<String, Object> conversation(@PathVariable String id) {
        Map<String, Object> c = new LinkedHashMap<>(found(id));
        c.put("messages", app.messages(id));
        return c;
    }

    @PatchMapping("/conversations/{id}")
    public Map<String, Object> renameConversation(@PathVariable String id, @RequestBody Map<String, Object> body) {
        found(id);
        app.renameConversation(id, String.valueOf(body.getOrDefault("title", "")), time.now());
        return app.conversation(id).orElseThrow();
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String id) {
        found(id);
        app.deleteConversation(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<Map<String, Object>> addMessage(@PathVariable String id, @RequestBody Map<String, Object> body) {
        found(id);
        String role = String.valueOf(body.getOrDefault("role", "user"));
        if (!List.of("user", "assistant").contains(role)) {
            throw ApiException.error(HttpStatus.BAD_REQUEST, "role must be user or assistant");
        }
        Object sqlResponse = body.get("sql_response");
        String mid = app.addMessage(id, role, (String) body.get("content"), (String) body.get("sql_code"),
                sqlResponse == null || sqlResponse instanceof String ? (String) sqlResponse : Json.write(sqlResponse),
                time.now());
        app.touchConversation(id, time.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(app.messages(id).stream()
                .filter(m -> mid.equals(m.get("id"))).findFirst().orElseThrow());
    }

    private Map<String, Object> found(String id) {
        if (!Ids.isUuid(id)) {
            throw ApiException.plain(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return app.conversation(id).orElseThrow(() -> ApiException.plain(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    // ------------------------------------------------------------------ acronyms

    @GetMapping("/acronyms")
    public List<Map<String, Object>> acronyms(@RequestParam(required = false) String name) {
        List<Map<String, Object>> all = app.acronyms();
        if (name == null || name.isBlank()) {
            return all;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return all.stream().filter(a -> String.valueOf(a.get("name")).toLowerCase(Locale.ROOT).contains(n)).toList();
    }

    @PostMapping("/acronyms")
    public ResponseEntity<Map<String, Object>> addAcronym(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        if (name == null) {
            throw ApiException.error(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (app.acronymNameExists(name, null)) {
            throw ApiException.error(HttpStatus.CONFLICT, "An acronym with this name already exists");
        }
        String id = app.addAcronym(name, str(body.get("description")), time.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(app.acronym(id).orElseThrow());
    }

    @PatchMapping("/acronyms/{id}")
    public Map<String, Object> updateAcronym(@PathVariable String id, @RequestBody Map<String, Object> body) {
        app.acronym(id).orElseThrow(() -> ApiException.error(HttpStatus.NOT_FOUND, "Acronym not found"));
        String name = str(body.get("name"));
        if (name != null && app.acronymNameExists(name, id)) {
            throw ApiException.error(HttpStatus.CONFLICT, "An acronym with this name already exists");
        }
        app.updateAcronym(id, name, str(body.get("description")), time.now());
        return app.acronym(id).orElseThrow();
    }

    @DeleteMapping("/acronyms/{id}")
    public Map<String, Object> deleteAcronym(@PathVariable String id) {
        if (app.deleteAcronym(id) == 0) {
            throw ApiException.error(HttpStatus.NOT_FOUND, "Acronym not found");
        }
        return Map.of("success", true);
    }

    // ------------------------------------------------------------------ custom prompts

    @GetMapping("/custom-prompts")
    public List<Map<String, Object>> prompts() {
        return app.prompts();
    }

    @GetMapping("/custom-prompts/{id}")
    public Map<String, Object> prompt(@PathVariable String id) {
        return app.prompt(id).orElseThrow(() -> ApiException.error(HttpStatus.NOT_FOUND, "Prompt not found"));
    }

    @PostMapping("/custom-prompts")
    public ResponseEntity<Map<String, Object>> addPrompt(@RequestBody Map<String, Object> body) {
        String content = str(body.get("content"));
        if (content == null) {
            throw ApiException.error(HttpStatus.BAD_REQUEST, "content is required");
        }
        String id = app.addPrompt(str(body.get("name")), content, user.id(), time.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(app.prompt(id).orElseThrow());
    }

    @PatchMapping("/custom-prompts/{id}")
    public Map<String, Object> updatePrompt(@PathVariable String id, @RequestBody Map<String, Object> body) {
        prompt(id);
        app.updatePrompt(id, str(body.get("name")), str(body.get("content")), time.now());
        return app.prompt(id).orElseThrow();
    }

    @DeleteMapping("/custom-prompts/{id}")
    public Map<String, Object> deletePrompt(@PathVariable String id) {
        if (app.deletePrompt(id) == 0) {
            throw ApiException.error(HttpStatus.NOT_FOUND, "Prompt not found");
        }
        return Map.of("success", true);
    }

    // ------------------------------------------------------------------ configurations (FR-CFG-02)

    @GetMapping(path = {"/configurations/visualization", "/configurations/pii-detection", "/configurations/semantic-compilation",
        "/configurations/distinct-value-probing", "/configurations/sql-query-timeout"})
    public Map<String, Object> configuration(jakarta.servlet.http.HttpServletRequest request) {
        return configuration(lastSegment(request));
    }

    static String lastSegment(jakarta.servlet.http.HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.substring(uri.lastIndexOf('/') + 1);
    }

    Map<String, Object> configuration(String name) {
        return switch (name) {
            case "visualization" -> Map.of("enabled", settings.visualizationEnabled());
            case "pii-detection" -> Map.of("enabled", settings.piiDetectionEnabled());
            case "semantic-compilation" -> Map.of("enabled", settings.semanticCompilationEnabled());
            case "distinct-value-probing" -> Map.of("enabled", settings.distinctValueProbingEnabled());
            case "sql-query-timeout" -> Map.of("seconds", settings.sqlQueryTimeoutSeconds());
            default -> throw ApiException.error(HttpStatus.NOT_FOUND, "Unknown configuration");
        };
    }

    @PutMapping(path = {"/configurations/visualization", "/configurations/pii-detection", "/configurations/semantic-compilation",
        "/configurations/distinct-value-probing", "/configurations/sql-query-timeout"})
    public Map<String, Object> setConfiguration(jakarta.servlet.http.HttpServletRequest request,
            @RequestBody Map<String, Object> body) {
        String name = lastSegment(request);
        if ("sql-query-timeout".equals(name)) {
            Object s = body.get("seconds");
            if (!(s instanceof Number n) || n.intValue() < 1 || n.intValue() > 3600) {
                throw ApiException.error(HttpStatus.BAD_REQUEST, "seconds must be an integer between 1 and 3600");
            }
            settings.set(RuntimeSettings.SQL_TIMEOUT, n.intValue());
            return configuration(name);
        }
        if (!(body.get("enabled") instanceof Boolean enabled)) {
            throw ApiException.error(HttpStatus.BAD_REQUEST, "enabled must be a boolean");
        }
        String key = switch (name) {
            case "visualization" -> RuntimeSettings.VISUALIZATION;
            case "pii-detection" -> RuntimeSettings.PII_DETECTION;
            case "semantic-compilation" -> RuntimeSettings.SEMANTIC_COMPILATION;
            case "distinct-value-probing" -> RuntimeSettings.DISTINCT_PROBING;
            default -> throw ApiException.error(HttpStatus.NOT_FOUND, "Unknown configuration");
        };
        settings.set(key, enabled);
        if ("semantic-compilation".equals(name) && enabled) {
            // enabling triggers a run without waiting for its result
            compilation.trigger();
        }
        return configuration(name);
    }

    // ------------------------------------------------------------------ analytics (FR-GOV-13)

    @GetMapping("/analytics")
    public Map<String, Object> analytics(@RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> all = app.analytics(Math.max(1, skip + limit));
        List<Map<String, Object>> page = all.stream().skip(skip).limit(limit).map(a -> {
            Map<String, Object> m = new LinkedHashMap<>(a);
            m.put("question_timestamp", a.get("created_at"));
            m.put("answer_timestamp", a.get("answered_at"));
            return m;
        }).toList();
        return Map.of("data", page, "count", page.size());
    }

    static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
