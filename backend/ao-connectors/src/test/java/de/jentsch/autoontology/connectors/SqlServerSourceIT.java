// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

/** SQL Server via Testcontainers, only in {@code integrationTest} (NFR-TST-02). */
@Tag("integration")
class SqlServerSourceIT extends ConnectorContract {
    static MSSQLServerContainer mssql;

    @BeforeAll
    static void start() throws Exception {
        mssql = new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU16-ubuntu-22.04").acceptLicense();
        mssql.start();
        try (var c = java.sql.DriverManager.getConnection(mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword());
                var st = c.createStatement()) {
            st.execute("CREATE SCHEMA shop");
            st.execute("CREATE TABLE shop.customers (customer_id INT NOT NULL PRIMARY KEY, customer_name NVARCHAR(50))");
            st.execute("CREATE TABLE shop.orders (order_id INT PRIMARY KEY, customer_id INT"
                    + " REFERENCES shop.customers(customer_id), amount DECIMAL(10,2))");
            st.execute("CREATE VIEW shop.v_orders AS SELECT o.order_id, c.customer_name FROM shop.orders o"
                    + " JOIN shop.customers c ON c.customer_id = o.customer_id");
            st.execute("INSERT INTO shop.customers VALUES (1, 'Ann'), (2, 'Bob'), (3, 'Cid')");
        }
    }

    @AfterAll
    static void stop() {
        mssql.stop();
    }

    @Override
    protected SourceDatabase source() {
        return Connectors.open(new ConnectionSpec("mssql", mssql.getJdbcUrl(), mssql.getUsername(), mssql.getPassword(),
                null, List.of("shop"), null, null));
    }

    @Override
    protected String expectedDialect() {
        return "tsql";
    }
}
