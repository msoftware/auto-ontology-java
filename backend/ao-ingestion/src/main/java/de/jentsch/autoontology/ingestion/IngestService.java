// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.connectors.ConnectionSpec;
import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.connectors.TableFilter;
import de.jentsch.autoontology.ingestion.QueryHistoryParser.ColumnPair;
import de.jentsch.autoontology.ingestion.QueryHistoryParser.ColumnRef;
import de.jentsch.autoontology.ingestion.QueryHistoryParser.Parsed;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.vector.VectorStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code run_ingest} (FR-ING-01): extract, filter, diff, parse the query history, PII, embed,
 * replay rules when the compilation is disabled. Runs are serialized.
 */
public class IngestService {
    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** Optional stage hooks (PII detection, rule replay, dataset sync); failures never fail the ingest (NFR-REL-01). */
    public interface Stage {
        default void afterCatalogWrite(String databaseId, String databaseName) {}

        default void afterIngest(String databaseId, String databaseName) {}
    }

    /** Summary of one ingest. */
    public record Result(String databaseName, String databaseId, int tables, int columns, int added, int removed,
            int queries, int embedded) {}

    private final ConnectionService connections;
    private final CatalogWriter writer;
    private final CatalogRepository catalog;
    private final SemanticRepository semantic;
    private final DataLayerEmbedder embedder;
    private final VectorStore vectors;
    private final RuntimeSettings settings;
    private final AoTime time;
    private final List<Stage> stages = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Instant lastFinished;

    /** Without semantic repository: deleting a database leaves its semantic layer in the store. */
    public IngestService(ConnectionService connections, CatalogWriter writer, CatalogRepository catalog,
            DataLayerEmbedder embedder, VectorStore vectors, RuntimeSettings settings, AoTime time) {
        this(connections, writer, catalog, null, embedder, vectors, settings, time);
    }

    public IngestService(ConnectionService connections, CatalogWriter writer, CatalogRepository catalog,
            SemanticRepository semantic, DataLayerEmbedder embedder, VectorStore vectors, RuntimeSettings settings,
            AoTime time) {
        this.connections = connections;
        this.writer = writer;
        this.catalog = catalog;
        this.semantic = semantic;
        this.embedder = embedder;
        this.vectors = vectors;
        this.settings = settings;
        this.time = time;
    }

    public void addStage(Stage stage) {
        stages.add(stage);
    }

    public Optional<Instant> lastFinished() {
        return Optional.ofNullable(lastFinished);
    }

    /** Ingests every connection of the ingestion precedence; errors of one database do not stop the others. */
    public List<Result> runAll() {
        List<Result> out = new java.util.ArrayList<>();
        for (ConnectionSpec spec : connections.ingestConnections()) {
            try {
                out.add(run(spec));
            } catch (RuntimeException e) {
                log.error("Ingest of {} failed: {}", spec.name(), e.getMessage(), e);
            }
        }
        lastFinished = time.now();
        return out;
    }

    public Result run(ConnectionSpec spec) {
        lock.lock();
        try {
            return doRun(spec);
        } finally {
            lock.unlock();
        }
    }

    private Result doRun(ConnectionSpec spec) {
        long start = System.nanoTime();
        Instant now = time.now();
        SourceDatabase db = connections.connector(spec);
        CatalogExtractor.Catalog cat = CatalogExtractor.extract(db, TableFilter.of(spec));
        // UI connections keep their stored JSON; configured ones store none
        Map<String, Object> conn = catalog.databaseByName(spec.name()).map(r -> (String) r.get("connection"))
                .filter(s -> s != null && !s.isBlank()).map(Json::readMap).orElse(null);
        CatalogWriter.WriteResult w = writer.write(cat, db.dialect(), conn, now);
        if (!w.removedTableIds().isEmpty() || !w.removedColumnIds().isEmpty()) {
            vectors.deleteByIds(VectorStore.DATA_LAYER, w.removedTableIds());
            vectors.deleteByIds(VectorStore.DATA_LAYER, w.removedColumnIds());
            vectors.deleteByIds(VectorStore.SEMANTIC_LAYER, w.removedAttributeIds());
        }
        int queries = writeQueryHistory(w, cat, now);
        for (Stage s : stages) {
            try {
                s.afterCatalogWrite(w.databaseId(), spec.name());
            } catch (RuntimeException e) {
                log.warn("Ingest stage {} failed for {} (ingest continues): {}", s.getClass().getSimpleName(),
                        spec.name(), e.getMessage());
            }
        }
        int embedded = embedder.embedDatabase(w.databaseId(), spec.name());
        for (Stage s : stages) {
            try {
                s.afterIngest(w.databaseId(), spec.name());
            } catch (RuntimeException e) {
                log.warn("Post-ingest stage {} failed for {}: {}", s.getClass().getSimpleName(), spec.name(),
                        e.getMessage());
            }
        }
        int columns = cat.tables().stream().mapToInt(t -> t.columns().size()).sum();
        log.info("Ingest {}: {} tables, {} columns, +{} -{} tables, {} queries, {} embedded in {} ms (compilation {})",
                spec.name(), cat.tables().size(), columns, w.added(), w.removed(), queries, embedded,
                (System.nanoTime() - start) / 1_000_000, settings.semanticCompilationEnabled() ? "on" : "off");
        return new Result(spec.name(), w.databaseId(), cat.tables().size(), columns, w.added(), w.removed(), queries,
                embedded);
    }

    /** FR-ING-01 step 4: dedupe, count, link tables/columns, record joins and unions. */
    private int writeQueryHistory(CatalogWriter.WriteResult w, CatalogExtractor.Catalog cat, Instant now) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Parsed> parsed = new LinkedHashMap<>();
        Map<String, Instant> last = new HashMap<>();
        for (Map<String, Object> q : cat.queries()) {
            Parsed p;
            try {
                p = QueryHistoryParser.parse(String.valueOf(q.get("query_text")));
            } catch (RuntimeException e) {
                // one statement the parser cannot walk must not fail the ingest of the database
                log.debug("Skipping history statement that could not be analysed: {}", e.toString());
                continue;
            }
            if (p == null) {
                continue;
            }
            parsed.putIfAbsent(p.hash(), p);
            counts.merge(p.hash(), 1, Integer::sum);
            Instant t = parseTime(q.get("end_time"));
            if (t != null) {
                last.merge(p.hash(), t, (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        Map<String, String> tableIdx = new HashMap<>();
        w.tableIds().forEach((k, v) -> {
            int dot = k.indexOf('.');
            tableIdx.put(QueryHistoryParser.key(k.substring(0, dot), k.substring(dot + 1)), v);
            tableIdx.putIfAbsent(QueryHistoryParser.key(null, k.substring(dot + 1)), v);
        });
        Map<String, String> colIdx = new HashMap<>();
        w.columnIds().forEach((k, v) -> {
            String[] parts = k.split("\\.", 3);
            colIdx.put(QueryHistoryParser.key(parts[0], parts[1]) + "." + parts[2].toLowerCase(Locale.ROOT), v);
            colIdx.putIfAbsent(QueryHistoryParser.key(null, parts[1]) + "." + parts[2].toLowerCase(Locale.ROOT), v);
        });
        for (Map.Entry<String, Parsed> e : parsed.entrySet()) {
            Parsed p = e.getValue();
            String sqlId = catalog.upsertSqlQuery(w.databaseId(), p.hash(), p.sql(), counts.get(e.getKey()),
                    last.get(e.getKey()), p.nodes(), p.joins().size(), p.unions().size(), now);
            for (String t : p.tables()) {
                String[] parts = t.split("\\.");
                String key = parts.length >= 2 ? QueryHistoryParser.key(parts[parts.length - 2], parts[parts.length - 1])
                        : QueryHistoryParser.key(null, parts[0]);
                String tid = tableIdx.get(key);
                if (tid != null) {
                    catalog.linkSqlTable(sqlId, tid);
                }
            }
            for (ColumnRef c : p.columns()) {
                String cid = column(colIdx, c);
                if (cid != null) {
                    catalog.linkSqlColumn(sqlId, cid);
                }
            }
            for (ColumnPair j : p.joins()) {
                observed("join", colIdx, j, sqlId);
            }
            for (ColumnPair u : p.unions()) {
                observed("union", colIdx, u, sqlId);
            }
        }
        return parsed.size();
    }

    private void observed(String kind, Map<String, String> colIdx, ColumnPair pair, String sqlId) {
        String a = column(colIdx, pair.left());
        String b = column(colIdx, pair.right());
        if (a != null && b != null && !a.equals(b)) {
            catalog.addObservedJoin(kind, a, b, sqlId + "|" + pair.condition());
        }
    }

    private static String column(Map<String, String> idx, ColumnRef c) {
        return idx.get(QueryHistoryParser.key(c.schema(), c.table()) + "." + c.column().toLowerCase(Locale.ROOT));
    }

    private static Instant parseTime(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).strip().replace(' ', 'T');
        try {
            // with an offset ("Z", "+02:00", "-05:00"; seconds may be missing), otherwise local time read as UTC
            return s.matches(".*T.*(Z|[+-]\\d{2}(:?\\d{2})?)$")
                    ? java.time.OffsetDateTime.parse(s).toInstant()
                    : java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * FR-ING-03: deleting a connection deletes its catalog, its semantic layer and the embeddings
     * (dal/reset.py::delete_all_data). The semantic layer goes first: Terms and ColumnAttributes
     * are only reachable through the catalog tables. Waits for a running ingest.
     */
    public Optional<String> deleteDatabase(String name) {
        Optional<Map<String, Object>> db = catalog.databaseByName(name);
        if (db.isEmpty()) {
            return Optional.empty();
        }
        lock.lock();
        try {
            if (semantic != null) {
                semantic.deleteSemanticLayer((String) db.get().get("id"));
            }
            catalog.deleteDatabase((String) db.get().get("id"));
        } finally {
            lock.unlock();
        }
        vectors.deleteByFilter(VectorStore.DATA_LAYER, name, null);
        vectors.deleteByFilter(VectorStore.SEMANTIC_LAYER, name, null);
        connections.refresh();
        return Optional.of(name);
    }
}
