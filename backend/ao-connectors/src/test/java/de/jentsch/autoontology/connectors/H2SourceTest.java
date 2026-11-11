// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class H2SourceTest extends ConnectorContract {
    static final String DDL = """
            CREATE SCHEMA shop;
            CREATE TABLE shop.customers (customer_id INTEGER NOT NULL PRIMARY KEY, customer_name VARCHAR(50));
            CREATE TABLE shop.orders (order_id INTEGER PRIMARY KEY, customer_id INTEGER REFERENCES shop.customers(customer_id),
                amount DECIMAL(10,2));
            CREATE VIEW shop.v_orders AS SELECT o.order_id, c.customer_name FROM shop.orders o JOIN shop.customers c
                ON c.customer_id = o.customer_id;
            COMMENT ON TABLE shop.customers IS 'All customers';
            INSERT INTO shop.customers VALUES (1, 'Ann'), (2, 'Bob'), (3, 'Cid');
            INSERT INTO shop.orders VALUES (10, 1, 5.5);
            """;

    protected boolean lower() {
        return true;
    }

    @Override
    protected SourceDatabase source() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:c" + System.nanoTime() + ";DB_CLOSE_DELAY=-1" + (lower() ? ";DATABASE_TO_LOWER=TRUE" : ""));
        for (String stmt : DDL.split(";\\s*\n")) {
            if (!stmt.isBlank()) {
                JdbcClient.create(ds).sql(stmt).update();
            }
        }
        return Connectors.wrap("shop_db", ds);
    }

    @Override
    protected String expectedDialect() {
        return "h2";
    }

    @Test
    void commentsAndQuotingFollowTheIdentifierCase() {
        SourceDatabase db = source();
        assertThat(db.getColumnComments()).anySatisfy(c -> assertThat(c.get("comment")).isEqualTo("All customers"));
        assertThat(db.qualify("shop", "customers")).isEqualTo("shop.customers");
        assertThat(db.quote("MixedCase")).isEqualTo("\"MixedCase\"");
        assertThat(db.getQueries()).isEmpty();
    }

    @Test
    void connectionStringNames() {
        assertThat(Connectors.nameFromUrl("jdbc:h2:mem:demo_sales;DB_CLOSE_DELAY=-1")).isEqualTo("demo_sales");
        assertThat(Connectors.nameFromUrl("jdbc:postgresql://host:5432/warehouse?user=x")).isEqualTo("warehouse");
        assertThat(Connectors.nameFromUrl("jdbc:sqlserver://h:1433;databaseName=Sales;encrypt=false")).isEqualTo("Sales");
        assertThat(Connectors.fromConnectionString("crm=jdbc:h2:mem:x").name()).isEqualTo("crm");
        assertThat(ConnectionSpec.redact("jdbc:postgresql://h/db?user=a&password=secret")).doesNotContain("secret");
        assertThat(new TableFilter(List.of(), "^ORD", "HIST$").allows("s", "ORDERS")).isTrue();
        assertThat(new TableFilter(List.of(), "^ORD", "HIST$").allows("s", "ORDERS_HIST")).isFalse();
        assertThat(new TableFilter(List.of("a"), null, null).allows("b", "X")).isFalse();
    }
}
