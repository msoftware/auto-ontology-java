// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.store;

import de.jentsch.autoontology.common.Ids;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** {@code semantic_compilation_history} (FR-SEM-07). */
public class HistoryRepository {
    private final StoreJdbc db;

    public HistoryRepository(StoreJdbc db) {
        this.db = db;
    }

    public String start(String databaseName, Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("now", now);
        p.put("db", databaseName);
        db.update("INSERT INTO semantic_compilation_history (id, started_at, database_name) VALUES (:id, :now, :db)", p);
        return id;
    }

    public void finish(String id, boolean success, String summary, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("st", success ? "succeeded" : "failed");
        p.put("sum", summary);
        p.put("now", now);
        db.update("UPDATE semantic_compilation_history SET finished_at = :now, status = :st, summary = :sum"
                + " WHERE id = :id", p);
    }

    public Optional<Map<String, Object>> last(String status) {
        return db.one("SELECT id, started_at, finished_at, status, database_name, summary"
                + " FROM semantic_compilation_history WHERE status = :st ORDER BY finished_at DESC"
                + " FETCH FIRST 1 ROWS ONLY", Map.of("st", status));
    }
}
