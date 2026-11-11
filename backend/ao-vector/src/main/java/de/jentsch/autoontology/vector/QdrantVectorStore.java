// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.vector;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchKeywords;

import de.jentsch.autoontology.common.AoProperties;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.Datatype;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.UpsertPoints;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Qdrant adapter over gRPC (FR-VEC-01..05). Qdrant reports a cosine similarity score; this adapter
 * converts it to the cosine distance of the original ({@code distance = 1 - score}) and translates
 * thresholds accordingly. Nothing outside this class sees scores.
 */
public class QdrantVectorStore implements VectorStore, AutoCloseable {
    private static final List<String> INDEXED = List.of("database_name", "label", "id");

    private final QdrantClient client;
    private final String prefix;
    private final int dimensions;
    private final int batchSize;
    private final Duration timeout;
    private final boolean float16;

    public QdrantVectorStore(AoProperties.Qdrant cfg, int dimensions) {
        QdrantGrpcClient.Builder b = QdrantGrpcClient.newBuilder(cfg.host(), cfg.port(), cfg.useTls())
                .withTimeout(cfg.timeout());
        if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) {
            b.withApiKey(cfg.apiKey());
        }
        this.client = new QdrantClient(b.build());
        this.prefix = cfg.collectionPrefix() == null ? "" : cfg.collectionPrefix();
        this.dimensions = dimensions;
        this.batchSize = Math.max(1, cfg.upsertBatchSize());
        this.timeout = cfg.timeout();
        this.float16 = "float16".equalsIgnoreCase(cfg.datatype());
    }

    private String name(String collection) {
        return prefix + collection;
    }

    /** Qdrant accepts only UUIDs or integers: deterministic UUIDv5-like id for other IDs (FR-VEC-01). */
    static PointId pointId(String collection, String id) {
        try {
            return PointIdFactory.id(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return PointIdFactory.id(UUID.nameUUIDFromBytes((collection + ":" + id).getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Override
    public void ensureCollections() {
        for (String c : List.of(DATA_LAYER, SEMANTIC_LAYER)) {
            String n = name(c);
            if (Boolean.TRUE.equals(get(client.collectionExistsAsync(n)))) {
                CollectionInfo info = get(client.getCollectionInfoAsync(n));
                VectorParams params = info.getConfig().getParams().getVectorsConfig().getParams();
                if (params.getSize() != dimensions || params.getDistance() != Distance.Cosine) {
                    throw new IllegalStateException("Qdrant collection " + n + " has size " + params.getSize()
                            + " and distance " + params.getDistance() + ", but ao.vector.dimensions=" + dimensions
                            + " with Cosine is configured. Rebuild the embeddings deliberately (reset/re-embed job).");
                }
                continue;
            }
            VectorParams.Builder vp = VectorParams.newBuilder().setSize(dimensions).setDistance(Distance.Cosine);
            if (float16) {
                vp.setDatatype(Datatype.Float16);
            }
            get(client.createCollectionAsync(n, vp.build()));
            for (String field : INDEXED) {
                get(client.createPayloadIndexAsync(n, field, PayloadSchemaType.Keyword, null, true, null, null));
            }
        }
    }

    @Override
    public void upsert(String collection, List<VectorRecord> records) {
        String n = name(collection);
        List<PointStruct> batch = new ArrayList<>();
        for (VectorRecord r : records) {
            if (r.vector().length != dimensions) {
                throw new IllegalStateException("Embedding dimension " + r.vector().length
                        + " does not match ao.vector.dimensions=" + dimensions);
            }
            Map<String, Value> payload = new HashMap<>();
            if (r.metadata() != null) {
                r.metadata().forEach((k, v) -> payload.put(k, toValue(v)));
            }
            payload.put("id", ValueFactory.value(r.id()));
            payload.put("text", ValueFactory.value(r.text() == null ? "" : r.text()));
            payload.put("label", ValueFactory.value(r.label() == null ? "" : r.label()));
            payload.put("name", ValueFactory.value(r.name() == null ? "" : r.name()));
            payload.put("database_name", r.databaseName() == null ? ValueFactory.nullValue()
                    : ValueFactory.value(r.databaseName()));
            batch.add(PointStruct.newBuilder()
                    .setId(pointId(n, r.id()))
                    .setVectors(VectorsFactory.vectors(r.vector()))
                    .putAllPayload(payload)
                    .build());
            if (batch.size() >= batchSize) {
                flush(n, batch);
            }
        }
        flush(n, batch);
    }

    private void flush(String n, List<PointStruct> batch) {
        if (batch.isEmpty()) {
            return;
        }
        // wait = true so that a directly following search sees the points (FR-VEC-05)
        get(client.upsertAsync(UpsertPoints.newBuilder().setCollectionName(n).setWait(true)
                .addAllPoints(batch).build(), timeout));
        batch.clear();
    }

    @Override
    public List<SearchHit> search(String collection, float[] vector, int k, VectorFilter filter, Double maxDistance) {
        SearchPoints.Builder sp = SearchPoints.newBuilder()
                .setCollectionName(name(collection))
                .setLimit(k)
                .setWithPayload(WithPayloadSelectorFactory.enable(true));
        for (float f : vector) {
            sp.addVector(f);
        }
        Filter f = toFilter(filter);
        if (f != null) {
            sp.setFilter(f);
        }
        if (maxDistance != null) {
            sp.setScoreThreshold((float) (1.0 - maxDistance));
        }
        List<SearchHit> hits = new ArrayList<>();
        for (ScoredPoint p : get(client.searchAsync(sp.build(), timeout))) {
            hits.add(new SearchHit(fromPayload(p.getPayloadMap()), 1.0 - p.getScore()));
        }
        return hits;
    }

    @Override
    public void deleteByIds(String collection, Collection<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String n = name(collection);
        get(client.deleteAsync(n, ids.stream().map(id -> pointId(n, id)).toList()));
    }

    @Override
    public void deleteByFilter(String collection, String databaseName, String label) {
        Filter.Builder fb = Filter.newBuilder();
        if (databaseName != null) {
            fb.addMust(matchKeyword("database_name", databaseName));
        }
        if (label != null) {
            fb.addMust(matchKeyword("label", label));
        }
        get(client.deleteAsync(name(collection), fb.build()));
    }

    @Override
    public long count(String collection) {
        return get(client.countAsync(name(collection)));
    }

    @Override
    public List<String> ids(String collection, String databaseName) {
        List<String> out = new ArrayList<>();
        PointId offset = null;
        do {
            ScrollPoints.Builder sb = ScrollPoints.newBuilder().setCollectionName(name(collection)).setLimit(512)
                    .setWithPayload(WithPayloadSelectorFactory.include(List.of("id")));
            if (databaseName != null) {
                sb.setFilter(Filter.newBuilder().addMust(matchKeyword("database_name", databaseName)).build());
            }
            if (offset != null) {
                sb.setOffset(offset);
            }
            ScrollResponse resp = get(client.scrollAsync(sb.build()));
            for (RetrievedPoint p : resp.getResultList()) {
                Value v = p.getPayloadMap().get("id");
                if (v != null) {
                    out.add(v.getStringValue());
                }
            }
            offset = resp.hasNextPageOffset() ? resp.getNextPageOffset() : null;
        } while (offset != null);
        return out;
    }

    @Override
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("store", "qdrant");
        try {
            get(client.healthCheckAsync(Duration.ofSeconds(5)));
            boolean data = Boolean.TRUE.equals(get(client.collectionExistsAsync(name(DATA_LAYER))));
            boolean sem = Boolean.TRUE.equals(get(client.collectionExistsAsync(name(SEMANTIC_LAYER))));
            h.put("ok", data && sem);
            h.put(DATA_LAYER, data);
            h.put(SEMANTIC_LAYER, sem);
        } catch (RuntimeException e) {
            h.put("ok", false);
            h.put("error", e.getMessage());
        }
        return h;
    }

    @Override
    public void close() {
        client.close();
    }

    private static Filter toFilter(VectorFilter filter) {
        if (filter == null) {
            return null;
        }
        Filter.Builder fb = Filter.newBuilder();
        boolean any = false;
        if (filter.databaseNames() != null && !filter.databaseNames().isEmpty()) {
            fb.addMust(matchKeywords("database_name", filter.databaseNames()));
            any = true;
        }
        if (filter.labels() != null && !filter.labels().isEmpty()) {
            fb.addMust(matchKeywords("label", filter.labels()));
            any = true;
        }
        if (filter.ids() != null && !filter.ids().isEmpty()) {
            fb.addMust(matchKeywords("id", filter.ids()));
            any = true;
        }
        return any ? fb.build() : null;
    }

    private static Value toValue(Object v) {
        if (v == null) {
            return ValueFactory.nullValue();
        }
        if (v instanceof Boolean b) {
            return ValueFactory.value(b);
        }
        if (v instanceof Integer || v instanceof Long) {
            return ValueFactory.value(((Number) v).longValue());
        }
        if (v instanceof Number n) {
            return ValueFactory.value(n.doubleValue());
        }
        if (v instanceof Collection<?> c) {
            return ValueFactory.list(c.stream().map(QdrantVectorStore::toValue).toList());
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Value> out = new HashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), toValue(val)));
            return ValueFactory.value(out);
        }
        return ValueFactory.value(String.valueOf(v));
    }

    private static Object fromValue(Value v) {
        return switch (v.getKindCase()) {
            case STRING_VALUE -> v.getStringValue();
            case INTEGER_VALUE -> v.getIntegerValue();
            case DOUBLE_VALUE -> v.getDoubleValue();
            case BOOL_VALUE -> v.getBoolValue();
            case LIST_VALUE -> v.getListValue().getValuesList().stream().map(QdrantVectorStore::fromValue).toList();
            case STRUCT_VALUE -> {
                Map<String, Object> m = new LinkedHashMap<>();
                v.getStructValue().getFieldsMap().forEach((k, val) -> m.put(k, fromValue(val)));
                yield m;
            }
            default -> null;
        };
    }

    private static VectorRecord fromPayload(Map<String, Value> payload) {
        Map<String, Object> meta = new LinkedHashMap<>();
        payload.forEach((k, v) -> meta.put(k, fromValue(v)));
        return new VectorRecord((String) meta.get("id"), (String) meta.get("text"), (String) meta.get("label"),
                (String) meta.get("name"), (String) meta.get("database_name"), meta, new float[0]);
    }

    private static <T> T get(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Qdrant", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Qdrant call failed: " + e.getCause().getMessage(), e.getCause());
        }
    }
}
