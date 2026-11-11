// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.oracle.OracleContainer;

/** Oracle Free via Testcontainers, only in {@code integrationTest} (NFR-TST-02). */
@Tag("integration")
class OracleSourceIT extends ConnectorContract {
    static OracleContainer oracle;

    @BeforeAll
    static void start() {
        oracle = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");
        oracle.start();
        SourceDatabase admin = Connectors.open(new ConnectionSpec("x", oracle.getJdbcUrl(), "system",
                oracle.getPassword(), null, List.of(), null, null));
        // DDL through a writable connection; the connector pool itself is read-only
        try (var c = java.sql.DriverManager.getConnection(oracle.getJdbcUrl(), "system", oracle.getPassword());
                var st = c.createStatement()) {
            st.execute("CREATE USER shop IDENTIFIED BY shop QUOTA UNLIMITED ON USERS");
            st.execute("CREATE TABLE shop.customers (customer_id NUMBER(10) NOT NULL PRIMARY KEY, customer_name VARCHAR2(50))");
            st.execute("CREATE TABLE shop.orders (order_id NUMBER(10) PRIMARY KEY, customer_id NUMBER(10)"
                    + " REFERENCES shop.customers(customer_id), amount NUMBER(10,2))");
            st.execute("CREATE VIEW shop.v_orders AS SELECT o.order_id, c.customer_name FROM shop.orders o"
                    + " JOIN shop.customers c ON c.customer_id = o.customer_id");
            st.execute("INSERT INTO shop.customers VALUES (1, 'Ann')");
            st.execute("INSERT INTO shop.customers VALUES (2, 'Bob')");
            st.execute("INSERT INTO shop.customers VALUES (3, 'Cid')");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            admin.close();
        }
    }

    @AfterAll
    static void stop() {
        oracle.stop();
    }

    @Override
    protected SourceDatabase source() {
        return Connectors.open(new ConnectionSpec("free", oracle.getJdbcUrl(), "system", oracle.getPassword(), null,
                List.of("SHOP"), null, null));
    }

    @Override
    protected String expectedDialect() {
        return "oracle";
    }
}
