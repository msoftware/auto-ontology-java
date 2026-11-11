// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.vector;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.store.StoreAutoConfiguration;
import de.jentsch.autoontology.store.StoreJdbc;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** {@code ao.vector.store = qdrant | memory} (FR-VEC-05). */
@AutoConfiguration(after = StoreAutoConfiguration.class)
public class VectorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public VectorStore aoVectorStore(AoProperties props, StoreJdbc store) {
        AoProperties.Vector v = props.vector();
        VectorStore vs = "memory".equalsIgnoreCase(v.store())
                ? new InMemoryVectorStore(v.qdrant().collectionPrefix(), v.dimensions(), v.persist() ? store : null)
                : new QdrantVectorStore(v.qdrant(), v.dimensions());
        vs.ensureCollections();
        return vs;
    }
}
