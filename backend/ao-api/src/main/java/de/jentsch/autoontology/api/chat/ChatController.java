// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.api.chat;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Chat API (FR-CHAT-01..03): SSE completions, watch, cancel. */
@RestController
@RequestMapping("${ao.api-prefix:/api}")
public class ChatController {
    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    private static void sseHeaders(HttpServletResponse resp) {
        resp.setHeader("Cache-Control", "no-cache, no-transform");
        resp.setHeader("X-Accel-Buffering", "no");
    }

    /** {@code x-auto-ontology-source} sets the analytics source; default {@code api}. */
    @PostMapping(path = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter completions(@RequestBody Map<String, Object> body,
            @RequestHeader(name = "x-auto-ontology-source", required = false) String source, HttpServletResponse resp) {
        sseHeaders(resp);
        ChatService.ChatRequest req = new ChatService.ChatRequest(
                body.get("question") == null ? null : String.valueOf(body.get("question")),
                body.get("evidence") == null ? null : String.valueOf(body.get("evidence")),
                body.get("conversation_id") == null ? null : String.valueOf(body.get("conversation_id")),
                body.get("prediction") instanceof Boolean b ? b : null,
                body.get("target_db") == null ? null : String.valueOf(body.get("target_db")));
        return chat.completions(req, source == null || source.isBlank() ? "api" : source);
    }

    @GetMapping(path = "/chat/watch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watch(@RequestParam(name = "conversation_id", required = false) String conversationId,
            HttpServletResponse resp) {
        sseHeaders(resp);
        return chat.watch(conversationId);
    }

    @PostMapping("/chat/cancel")
    public Map<String, Object> cancel(@RequestBody(required = false) Map<String, Object> body,
            @RequestParam(name = "conversation_id", required = false) String param) {
        Object id = body == null ? param : body.getOrDefault("conversation_id", param);
        return chat.cancel(id == null ? null : String.valueOf(id));
    }
}
