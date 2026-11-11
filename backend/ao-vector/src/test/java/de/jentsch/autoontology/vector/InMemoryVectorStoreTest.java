// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.jentsch.autoontology.store.StoreJdbc;
import java.util.List;
import liquibase.integration.spring.SpringLiquibase;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;

class InMemoryVectorStoreTest extends VectorStoreContract {
    @Override
    protected VectorStore store() {
        return new InMemoryVectorStore("", 4, null);
    }

    @Test
    void persistsIntoTheStoreAndReloads() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:vec_" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(ds);
        lb.setChangeLog("classpath:db/changelog/ao-changelog.yaml");
        lb.setResourceLoader(new DefaultResourceLoader());
        lb.afterPropertiesSet();
        StoreJdbc jdbc = new StoreJdbc(JdbcClient.create(ds));
        InMemoryVectorStore a = new InMemoryVectorStore("", 4, jdbc);
        a.ensureCollections();
        a.upsert(VectorStore.DATA_LAYER, List.of(rec("x", "db", "Table", 1, 2, 3, 4)));
        InMemoryVectorStore b = new InMemoryVectorStore("", 4, jdbc);
        b.ensureCollections();
        assertThat(b.ids(VectorStore.DATA_LAYER, "db")).containsExactly("x");
        // FR-VEC-02: a dimension mismatch fails with a clear message
        assertThatThrownBy(() -> new InMemoryVectorStore("", 8, jdbc).ensureCollections())
                .hasMessageContaining("ao.vector.dimensions");
    }
}
