// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.vector;

import static org.assertj.core.api.Assertions.assertThat;

import de.jentsch.autoontology.vector.VectorStore.SearchHit;
import de.jentsch.autoontology.vector.VectorStore.VectorFilter;
import de.jentsch.autoontology.vector.VectorStore.VectorRecord;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Shared contract (FR-VEC-03): every implementation returns the same order and (up to rounding)
 * the same cosine distances for the same vectors.
 */
abstract class VectorStoreContract {
    protected abstract VectorStore store();

    static VectorRecord rec(String id, String db, String label, float... v) {
        return new VectorRecord(id, "text " + id, label, "n" + id, db, Map.of("schema_name", "s"), v);
    }

    @Test
    void searchReturnsDistancesSmallestFirstAndHonoursFilters() {
        VectorStore s = store();
        s.ensureCollections();
        String a = UUID.randomUUID().toString();
        s.upsert(VectorStore.SEMANTIC_LAYER, List.of(
                rec(a, "db1", "Term", 1, 0, 0, 0),
                rec("non-uuid-b", "db1", "ColumnAttribute", 0.9f, 0.1f, 0, 0),
                rec("c", "db2", "Term", 0, 1, 0, 0)));
        List<SearchHit> hits = s.search(VectorStore.SEMANTIC_LAYER, new float[] {1, 0, 0, 0}, 10, VectorFilter.none(), null);
        assertThat(hits).extracting(SearchHit::id).containsExactly(a, "non-uuid-b", "c");
        assertThat(hits.get(0).distance()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(hits.get(2).distance()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(hits.get(1).meta("schema_name")).isEqualTo("s");

        assertThat(s.search(VectorStore.SEMANTIC_LAYER, new float[] {1, 0, 0, 0}, 10,
                VectorFilter.of("db1", "Term"), null)).extracting(SearchHit::id).containsExactly(a);
        assertThat(s.search(VectorStore.SEMANTIC_LAYER, new float[] {1, 0, 0, 0}, 10, VectorFilter.none(), 0.5))
                .hasSize(2);

        s.deleteByFilter(VectorStore.SEMANTIC_LAYER, "db2", null);
        assertThat(s.ids(VectorStore.SEMANTIC_LAYER, null)).containsExactlyInAnyOrder(a, "non-uuid-b");
        s.deleteByIds(VectorStore.SEMANTIC_LAYER, List.of("non-uuid-b"));
        assertThat(s.count(VectorStore.SEMANTIC_LAYER)).isEqualTo(1);
    }
}
