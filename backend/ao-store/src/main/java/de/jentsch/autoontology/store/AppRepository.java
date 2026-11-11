// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.store;

import de.jentsch.autoontology.common.Ids;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tables the original keeps in the Next.js frontend (section 4.2): conversations, messages,
 * custom prompts, configurations, acronyms, conversation analytics.
 */
public class AppRepository {
    private final StoreJdbc db;

    public AppRepository(StoreJdbc db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- conversations

    public List<Map<String, Object>> conversations() {
        return db.list("SELECT id, user_id, title, created_at, updated_at FROM app_conversations"
                + " ORDER BY updated_at DESC, id");
    }

    public Optional<Map<String, Object>> conversation(String id) {
        return db.one("SELECT id, user_id, title, created_at, updated_at FROM app_conversations WHERE id = :id",
                Map.of("id", id));
    }

    public void createConversation(String id, String userId, String title, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("u", userId);
        p.put("t", title);
        p.put("now", now);
        db.update("INSERT INTO app_conversations (id, user_id, title, created_at, updated_at)"
                + " VALUES (:id, :u, :t, :now, :now)", p);
    }

    public int renameConversation(String id, String title, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("t", title);
        p.put("now", now);
        return db.update("UPDATE app_conversations SET title = :t, updated_at = :now WHERE id = :id", p);
    }

    public void touchConversation(String id, Instant now) {
        db.update("UPDATE app_conversations SET updated_at = :now WHERE id = :id", Map.of("id", id, "now", now));
    }

    public int deleteConversation(String id) {
        return db.update("DELETE FROM app_conversations WHERE id = :id", Map.of("id", id));
    }

    /** Messages oldest first (FR-CHAT-05). */
    public List<Map<String, Object>> messages(String conversationId) {
        return db.list("SELECT id, conversation_id, role, content, sql_code, sql_response, created_at"
                + " FROM app_messages WHERE conversation_id = :id ORDER BY created_at, id", Map.of("id", conversationId));
    }

    public String addMessage(String conversationId, String role, String content, String sqlCode, String sqlResponse,
            Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("c", conversationId);
        p.put("r", role);
        p.put("content", content);
        p.put("sql", sqlCode);
        p.put("resp", sqlResponse);
        p.put("now", now);
        db.update("INSERT INTO app_messages (id, conversation_id, role, content, sql_code, sql_response, created_at)"
                + " VALUES (:id, :c, :r, :content, :sql, :resp, :now)", p);
        return id;
    }

    // ---------------------------------------------------------------- custom prompts

    public List<Map<String, Object>> prompts() {
        return db.list("SELECT id, name, content, created_by, created_at, updated_at FROM app_prompts"
                + " ORDER BY created_at, id");
    }

    public Optional<Map<String, Object>> prompt(String id) {
        return db.one("SELECT id, name, content, created_by, created_at, updated_at FROM app_prompts WHERE id = :id",
                Map.of("id", id));
    }

    public String addPrompt(String name, String content, String user, Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("c", content);
        p.put("u", user);
        p.put("now", now);
        db.update("INSERT INTO app_prompts (id, name, content, created_by, created_at, updated_at)"
                + " VALUES (:id, :n, :c, :u, :now, :now)", p);
        return id;
    }

    public int updatePrompt(String id, String name, String content, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("c", content);
        p.put("now", now);
        return db.update("UPDATE app_prompts SET name = COALESCE(:n, name), content = COALESCE(:c, content),"
                + " updated_at = :now WHERE id = :id", p);
    }

    public int deletePrompt(String id) {
        return db.update("DELETE FROM app_prompts WHERE id = :id", Map.of("id", id));
    }

    // ---------------------------------------------------------------- configurations

    public Optional<String> config(String key) {
        return db.one("SELECT config_value FROM app_configurations WHERE config_key = :k", Map.of("k", key))
                .map(r -> (String) r.get("config_value"));
    }

    public void setConfig(String key, String value, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("k", key);
        p.put("v", value);
        p.put("now", now);
        int n = db.update("UPDATE app_configurations SET config_value = :v, updated_at = :now WHERE config_key = :k", p);
        if (n == 0) {
            db.update("INSERT INTO app_configurations (config_key, config_value, updated_at) VALUES (:k, :v, :now)", p);
        }
    }

    // ---------------------------------------------------------------- acronyms

    public List<Map<String, Object>> acronyms() {
        return db.list("SELECT id, name, description, created_at, updated_at FROM app_acronyms ORDER BY LOWER(name)");
    }

    public Optional<Map<String, Object>> acronym(String id) {
        return db.one("SELECT id, name, description, created_at, updated_at FROM app_acronyms WHERE id = :id",
                Map.of("id", id));
    }

    public boolean acronymNameExists(String name, String exceptId) {
        Map<String, Object> p = new HashMap<>();
        p.put("n", name);
        p.put("x", exceptId == null ? "" : exceptId);
        return db.count("SELECT COUNT(*) FROM app_acronyms WHERE name = :n AND id <> :x", p) > 0;
    }

    public String addAcronym(String name, String description, Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("d", description);
        p.put("now", now);
        db.update("INSERT INTO app_acronyms (id, name, description, created_at, updated_at)"
                + " VALUES (:id, :n, :d, :now, :now)", p);
        return id;
    }

    public int updateAcronym(String id, String name, String description, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("d", description);
        p.put("now", now);
        return db.update("UPDATE app_acronyms SET name = COALESCE(:n, name),"
                + " description = COALESCE(:d, description), updated_at = :now WHERE id = :id", p);
    }

    public int deleteAcronym(String id) {
        return db.update("DELETE FROM app_acronyms WHERE id = :id", Map.of("id", id));
    }

    // ---------------------------------------------------------------- analytics

    public String addAnalytics(String userId, String conversationId, String source, String question, Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("u", userId);
        p.put("c", conversationId);
        p.put("s", source);
        p.put("q", question);
        p.put("now", now);
        db.update("INSERT INTO app_conversation_analytics (id, user_id, conversation_id, source, question, created_at)"
                + " VALUES (:id, :u, :c, :s, :q, :now)", p);
        return id;
    }

    public void completeAnalytics(String id, String answer, String reasoning, String sql, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("a", answer);
        p.put("r", reasoning);
        p.put("s", sql);
        p.put("now", now);
        db.update("UPDATE app_conversation_analytics SET answer = :a, reasoning = :r, sql_code = :s,"
                + " answered_at = :now WHERE id = :id", p);
    }

    public List<Map<String, Object>> analytics(int limit) {
        return db.list("SELECT id, user_id, conversation_id, source, question, answer, reasoning, sql_code,"
                + " created_at, answered_at FROM app_conversation_analytics ORDER BY created_at DESC"
                + " FETCH FIRST " + limit + " ROWS ONLY");
    }
}
