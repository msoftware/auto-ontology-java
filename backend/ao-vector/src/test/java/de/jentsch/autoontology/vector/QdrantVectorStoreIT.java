// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.vector;

import de.jentsch.autoontology.common.AoProperties;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.qdrant.QdrantContainer;

/** Same contract against a real Qdrant (task {@code integrationTest}, NFR-TST-02). */
@Tag("integration")
class QdrantVectorStoreIT extends VectorStoreContract {
    /** Pinned like docker-compose.yml, never latest (FR-VEC-04). */
    static final String IMAGE = "qdrant/qdrant:v1.20.0";

    static QdrantContainer qdrant;

    @BeforeAll
    static void start() {
        qdrant = new QdrantContainer(IMAGE);
        qdrant.start();
    }

    @AfterAll
    static void stop() {
        qdrant.stop();
    }

    @Override
    protected VectorStore store() {
        AoProperties.Qdrant cfg = new AoProperties.Qdrant(qdrant.getHost(), qdrant.getGrpcPort(), false, null,
                Duration.ofSeconds(30), "it_" + System.nanoTime() + "_", 256, "float32");
        return new QdrantVectorStore(cfg, 4);
    }
}
