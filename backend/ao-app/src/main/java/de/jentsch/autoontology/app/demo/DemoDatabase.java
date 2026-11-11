// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.app.demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo source database "Sales" (FR-DEMO-02): own data, seed 42, period 2024-07-01 to 2026-06-30,
 * with the built-in demo cases (missing FK, text date, case trap, comments, query history).
 */
public final class DemoDatabase {
    private static final Logger log = LoggerFactory.getLogger(DemoDatabase.class);
    static final LocalDate START = LocalDate.of(2024, 7, 1);
    static final LocalDate END = LocalDate.of(2026, 6, 30);

    private DemoDatabase() {}

    static final String[][] STATES = {
        {"California", "CA"}, {"Texas", "TX"}, {"New York", "NY"}, {"Florida", "FL"}, {"Illinois", "IL"},
        {"Washington", "WA"}, {"Georgia", "GA"}, {"Colorado", "CO"}, {"Arizona", "AZ"}, {"Ohio", "OH"},
        {"Oregon", "OR"}, {"Nevada", "NV"}, {"Michigan", "MI"}, {"Virginia", "VA"}, {"Massachusetts", "MA"}};
    static final String[] CITY_PREFIX = {"Spring", "River", "Oak", "Lake", "Fair", "Green", "Clear", "Rock", "Maple",
        "Cedar", "Pine", "Silver"};
    static final String[] CITY_SUFFIX = {"field", "ton", "view", "dale", "port", "wood", "ville", "burg", "side",
        "haven"};
    static final String[] COLORS = {"Black", "White", "Red", "Blue", "Green", "Yellow", "Grey", "Orange", "Purple",
        "Brown"};
    static final String[] BRANDS = {"Northwind", "Contoso", "Fabrikam", "Tailspin", "Litware", "Adatum"};
    static final String[] ITEMS = {"USB Cable", "Desk Lamp", "Coffee Mug", "Notebook", "Backpack", "Water Bottle",
        "Mouse Pad", "Headphones", "Phone Case", "T-Shirt", "Hoodie", "Cap", "Pen Set", "Sticky Notes"};
    static final String[] CATEGORIES = {"Retail", "Wholesale", "Online"};
    static final String[] FIRST = {"Anna", "Ben", "Clara", "David", "Emma", "Felix", "Grace", "Henry", "Ivy", "Jack",
        "Kira", "Liam", "Mia", "Noah", "Olivia", "Paul", "Quinn", "Ruby", "Sam", "Tara"};
    static final String[] LAST = {"Miller", "Smith", "Brown", "Garcia", "Lee", "Walker", "Young", "King", "Wright",
        "Lopez", "Hill", "Green", "Adams", "Baker", "Nelson"};
    static final String[] COMPANY = {"Traders", "Supplies", "Goods", "Mart", "Store", "Outlet", "Depot", "Shop"};

    /** Creates and fills the demo database once per JVM (in-memory, DB_CLOSE_DELAY=-1). */
    public static synchronized void create(String url) {
        try (Connection c = DriverManager.getConnection(url)) {
            try (Statement st = c.createStatement()) {
                if (st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = 'SALES'")
                        .next() && exists(st)) {
                    return;
                }
            }
            c.setAutoCommit(false);
            ddl(c);
            data(c);
            c.commit();
            log.info("Demo database created at {}", url);
        } catch (SQLException e) {
            throw new IllegalStateException("Demo database could not be created", e);
        }
    }

    private static boolean exists(Statement st) throws SQLException {
        var rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = 'SALES'");
        rs.next();
        return rs.getInt(1) > 0;
    }

    private static void ddl(Connection c) throws SQLException {
        String[] ddl = {
            "CREATE SCHEMA APPLICATION", "CREATE SCHEMA SALES", "CREATE SCHEMA WAREHOUSE", "CREATE SCHEMA PURCHASING",
            "CREATE SCHEMA DEMO_META",
            "CREATE TABLE APPLICATION.STATE_PROVINCES (STATE_PROVINCE_ID INT PRIMARY KEY, STATE_PROVINCE_NAME VARCHAR(60)"
                    + " NOT NULL, STATE_PROVINCE_CODE VARCHAR(5) NOT NULL)",
            "CREATE TABLE APPLICATION.CITIES (CITY_ID INT PRIMARY KEY, CITY_NAME VARCHAR(60) NOT NULL, STATE_PROVINCE_ID"
                    + " INT NOT NULL REFERENCES APPLICATION.STATE_PROVINCES(STATE_PROVINCE_ID))",
            "CREATE TABLE APPLICATION.PEOPLE (PERSON_ID INT PRIMARY KEY, FULL_NAME VARCHAR(80) NOT NULL, IS_SALESPERSON"
                    + " BOOLEAN NOT NULL)",
            "CREATE TABLE WAREHOUSE.COLORS (COLOR_ID INT PRIMARY KEY, COLOR_NAME VARCHAR(30) NOT NULL)",
            "CREATE TABLE WAREHOUSE.STOCK_ITEMS (STOCK_ITEM_ID INT PRIMARY KEY, STOCK_ITEM_NAME VARCHAR(100) NOT NULL,"
                    + " UNIT_PRICE DECIMAL(10,2) NOT NULL, BRAND VARCHAR(40), COLOR_ID INT REFERENCES"
                    + " WAREHOUSE.COLORS(COLOR_ID))",
            "CREATE TABLE PURCHASING.SUPPLIERS (SUPPLIER_ID INT PRIMARY KEY, SUPPLIER_NAME VARCHAR(80) NOT NULL)",
            "CREATE TABLE SALES.CUSTOMERS (CUSTOMER_ID INT PRIMARY KEY, CUSTOMER_NAME VARCHAR(100) NOT NULL,"
                    + " CUSTOMER_CATEGORY VARCHAR(20) NOT NULL, DELIVERY_CITY_ID INT REFERENCES APPLICATION.CITIES(CITY_ID),"
                    + " EMAIL VARCHAR(120), PHONE VARCHAR(30))",
            "CREATE TABLE SALES.ORDERS (ORDER_ID INT PRIMARY KEY, CUSTOMER_ID INT NOT NULL REFERENCES"
                    + " SALES.CUSTOMERS(CUSTOMER_ID), ORDER_DATE DATE NOT NULL, LEGACY_ORDER_DATE VARCHAR(8),"
                    + " SALESPERSON_ID INT REFERENCES APPLICATION.PEOPLE(PERSON_ID))",
            "CREATE TABLE SALES.ORDER_LINES (ORDER_LINE_ID INT PRIMARY KEY, ORDER_ID INT NOT NULL REFERENCES"
                    + " SALES.ORDERS(ORDER_ID), STOCK_ITEM_ID INT NOT NULL REFERENCES WAREHOUSE.STOCK_ITEMS(STOCK_ITEM_ID),"
                    + " QUANTITY INT NOT NULL, UNIT_PRICE DECIMAL(10,2) NOT NULL)",
            // deliberately WITHOUT a declared FK on CUSTOMER_ID (LLM FK detection and semantic FK demo)
            "CREATE TABLE SALES.INVOICES (INVOICE_ID INT PRIMARY KEY, ORDER_ID INT NOT NULL REFERENCES"
                    + " SALES.ORDERS(ORDER_ID), CUSTOMER_ID INT NOT NULL, INVOICE_DATE DATE NOT NULL)",
            "CREATE TABLE SALES.INVOICE_LINES (INVOICE_LINE_ID INT PRIMARY KEY, INVOICE_ID INT NOT NULL REFERENCES"
                    + " SALES.INVOICES(INVOICE_ID), STOCK_ITEM_ID INT NOT NULL REFERENCES"
                    + " WAREHOUSE.STOCK_ITEMS(STOCK_ITEM_ID), QUANTITY INT NOT NULL, EXTENDED_PRICE DECIMAL(12,2) NOT NULL)",
            // pure FK table for the bridge-table demo
            "CREATE TABLE SALES.ITEM_SUPPLIER_LINK (STOCK_ITEM_ID INT NOT NULL REFERENCES"
                    + " WAREHOUSE.STOCK_ITEMS(STOCK_ITEM_ID), SUPPLIER_ID INT NOT NULL REFERENCES"
                    + " PURCHASING.SUPPLIERS(SUPPLIER_ID), PRIMARY KEY (STOCK_ITEM_ID, SUPPLIER_ID))",
            "CREATE TABLE DEMO_META.QUERY_HISTORY (END_TIME TIMESTAMP NOT NULL, QUERY_TEXT VARCHAR(4000) NOT NULL)",
            "COMMENT ON TABLE SALES.CUSTOMERS IS 'Customers who buy stock items; category is Retail, Wholesale or Online'",
            "COMMENT ON TABLE SALES.ORDERS IS 'Customer orders placed with a salesperson'",
            "COMMENT ON TABLE SALES.INVOICES IS 'Invoices issued for orders'",
            "COMMENT ON COLUMN SALES.INVOICE_LINES.EXTENDED_PRICE IS 'Line revenue: quantity times unit price'",
            "COMMENT ON COLUMN SALES.ORDERS.LEGACY_ORDER_DATE IS 'Order date from the legacy system'",
            "COMMENT ON TABLE WAREHOUSE.STOCK_ITEMS IS 'Products held in stock'",
        };
        try (Statement st = c.createStatement()) {
            for (String s : ddl) {
                st.execute(s);
            }
        }
    }

    private static void data(Connection c) throws SQLException {
        Random r = new Random(42);
        batch(c, "INSERT INTO APPLICATION.STATE_PROVINCES VALUES (?, ?, ?)", rows -> {
            for (int i = 0; i < STATES.length; i++) {
                rows.add(new Object[] {i + 1, STATES[i][0], STATES[i][1]});
            }
        });
        int cities = 60;
        batch(c, "INSERT INTO APPLICATION.CITIES VALUES (?, ?, ?)", rows -> {
            for (int i = 1; i <= cities; i++) {
                String name = CITY_PREFIX[(i - 1) % CITY_PREFIX.length] + CITY_SUFFIX[(i * 7) % CITY_SUFFIX.length];
                rows.add(new Object[] {i, name + (i > CITY_PREFIX.length * CITY_SUFFIX.length / 2 ? " " + i : ""),
                    1 + r.nextInt(STATES.length)});
            }
        });
        batch(c, "INSERT INTO APPLICATION.PEOPLE VALUES (?, ?, ?)", rows -> {
            for (int i = 1; i <= 20; i++) {
                rows.add(new Object[] {i, FIRST[i - 1] + " " + LAST[(i * 3) % LAST.length], i <= 10});
            }
        });
        batch(c, "INSERT INTO WAREHOUSE.COLORS VALUES (?, ?)", rows -> {
            for (int i = 0; i < COLORS.length; i++) {
                rows.add(new Object[] {i + 1, COLORS[i]});
            }
        });
        double[] price = new double[81];
        batch(c, "INSERT INTO WAREHOUSE.STOCK_ITEMS VALUES (?, ?, ?, ?, ?)", rows -> {
            for (int i = 1; i <= 80; i++) {
                int color = 1 + r.nextInt(COLORS.length);
                price[i] = Math.round((3 + r.nextDouble() * 120) * 100) / 100.0;
                rows.add(new Object[] {i, ITEMS[(i - 1) % ITEMS.length] + " (" + COLORS[color - 1] + ") " + ((i - 1) / ITEMS.length + 1),
                    price[i], BRANDS[r.nextInt(BRANDS.length)], color});
            }
        });
        batch(c, "INSERT INTO PURCHASING.SUPPLIERS VALUES (?, ?)", rows -> {
            for (int i = 1; i <= 12; i++) {
                rows.add(new Object[] {i, LAST[i % LAST.length] + " " + COMPANY[i % COMPANY.length] + " Ltd"});
            }
        });
        batch(c, "INSERT INTO SALES.ITEM_SUPPLIER_LINK VALUES (?, ?)", rows -> {
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (rows.size() < 100) {
                int item = 1 + r.nextInt(80);
                int sup = 1 + r.nextInt(12);
                if (seen.add(item + "-" + sup)) {
                    rows.add(new Object[] {item, sup});
                }
            }
        });
        int customers = 200;
        batch(c, "INSERT INTO SALES.CUSTOMERS VALUES (?, ?, ?, ?, ?, ?)", rows -> {
            for (int i = 1; i <= customers; i++) {
                String name = LAST[r.nextInt(LAST.length)] + " " + COMPANY[r.nextInt(COMPANY.length)] + " " + i;
                // case trap: the stored category is 'Wholesale' with a capital W
                String category = CATEGORIES[r.nextInt(10) < 5 ? 0 : r.nextInt(10) < 6 ? 1 : 2];
                rows.add(new Object[] {i, name, category, 1 + r.nextInt(cities),
                    "contact" + i + "@example.com", "+1-555-" + String.format("%04d", r.nextInt(10000))});
            }
        });
        long days = ChronoUnit.DAYS.between(START, END) + 1;
        List<Object[]> orders = new ArrayList<>();
        List<Object[]> lines = new ArrayList<>();
        List<Object[]> invoices = new ArrayList<>();
        List<Object[]> invoiceLines = new ArrayList<>();
        int lineId = 1;
        int invoiceId = 1;
        int invoiceLineId = 1;
        for (int o = 1; o <= 3000; o++) {
            LocalDate date = START.plusDays(r.nextInt((int) days));
            int customer = 1 + r.nextInt(customers);
            orders.add(new Object[] {o, customer, java.sql.Date.valueOf(date),
                date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), 1 + r.nextInt(10)});
            int n = 1 + r.nextInt(5);
            boolean invoiced = r.nextInt(100) < 93;
            int inv = invoiceId;
            if (invoiced) {
                invoices.add(new Object[] {invoiceId++, o, customer,
                    java.sql.Date.valueOf(date.plusDays(r.nextInt(5)).isAfter(END) ? END : date.plusDays(r.nextInt(5)))});
            }
            for (int l = 0; l < n; l++) {
                int item = 1 + r.nextInt(80);
                int qty = 1 + r.nextInt(12);
                lines.add(new Object[] {lineId++, o, item, qty, price[item]});
                if (invoiced) {
                    invoiceLines.add(new Object[] {invoiceLineId++, inv, item, qty,
                        Math.round(qty * price[item] * 100) / 100.0});
                }
            }
        }
        batch(c, "INSERT INTO SALES.ORDERS VALUES (?, ?, ?, ?, ?)", rows -> rows.addAll(orders));
        batch(c, "INSERT INTO SALES.ORDER_LINES VALUES (?, ?, ?, ?, ?)", rows -> rows.addAll(lines));
        batch(c, "INSERT INTO SALES.INVOICES VALUES (?, ?, ?, ?)", rows -> rows.addAll(invoices));
        batch(c, "INSERT INTO SALES.INVOICE_LINES VALUES (?, ?, ?, ?, ?)", rows -> rows.addAll(invoiceLines));
        // END_TIME relative to the real current time: the ingest only reads the last 24 hours
        Instant now = Instant.now();
        batch(c, "INSERT INTO DEMO_META.QUERY_HISTORY VALUES (?, ?)", rows -> {
            int i = 0;
            for (String q : HISTORY) {
                rows.add(new Object[] {Timestamp.from(now.minus(1 + (i++ * 37) % 1200, ChronoUnit.MINUTES)), q});
            }
        });
    }

    static final String[] HISTORY = {
        "SELECT EXTRACT(YEAR FROM i.INVOICE_DATE) AS yr, EXTRACT(MONTH FROM i.INVOICE_DATE) AS mon,"
                + " SUM(il.EXTENDED_PRICE) AS revenue FROM SALES.INVOICES i JOIN SALES.INVOICE_LINES il"
                + " ON il.INVOICE_ID = i.INVOICE_ID GROUP BY EXTRACT(YEAR FROM i.INVOICE_DATE), EXTRACT(MONTH FROM"
                + " i.INVOICE_DATE)",
        "SELECT c.CUSTOMER_NAME, SUM(il.EXTENDED_PRICE) AS revenue FROM SALES.INVOICES i JOIN SALES.INVOICE_LINES il"
                + " ON il.INVOICE_ID = i.INVOICE_ID JOIN SALES.CUSTOMERS c ON c.CUSTOMER_ID = i.CUSTOMER_ID GROUP BY"
                + " c.CUSTOMER_NAME ORDER BY revenue DESC LIMIT 10",
        "SELECT COUNT(*) FROM SALES.CUSTOMERS c WHERE c.CUSTOMER_CATEGORY = 'Wholesale'",
        "SELECT COUNT(*) FROM SALES.CUSTOMERS c WHERE c.CUSTOMER_CATEGORY = 'Retail'",
        "SELECT sp.STATE_PROVINCE_NAME, SUM(il.EXTENDED_PRICE) FROM SALES.INVOICE_LINES il JOIN SALES.INVOICES i ON"
                + " i.INVOICE_ID = il.INVOICE_ID JOIN SALES.CUSTOMERS c ON c.CUSTOMER_ID = i.CUSTOMER_ID JOIN"
                + " APPLICATION.CITIES ci ON ci.CITY_ID = c.DELIVERY_CITY_ID JOIN APPLICATION.STATE_PROVINCES sp ON"
                + " sp.STATE_PROVINCE_ID = ci.STATE_PROVINCE_ID GROUP BY sp.STATE_PROVINCE_NAME",
        "SELECT si.STOCK_ITEM_NAME, SUM(ol.QUANTITY) FROM SALES.ORDER_LINES ol JOIN WAREHOUSE.STOCK_ITEMS si ON"
                + " si.STOCK_ITEM_ID = ol.STOCK_ITEM_ID GROUP BY si.STOCK_ITEM_NAME ORDER BY 2 DESC LIMIT 5",
        "SELECT o.ORDER_ID, o.ORDER_DATE FROM SALES.ORDERS o WHERE o.ORDER_DATE >= DATE '2025-01-01'",
        "SELECT p.FULL_NAME, COUNT(o.ORDER_ID) FROM SALES.ORDERS o JOIN APPLICATION.PEOPLE p ON p.PERSON_ID ="
                + " o.SALESPERSON_ID WHERE p.IS_SALESPERSON = TRUE GROUP BY p.FULL_NAME",
        "SELECT SUM(il.EXTENDED_PRICE) - SUM(il.QUANTITY * si.UNIT_PRICE * 0.6) AS margin FROM SALES.INVOICE_LINES il"
                + " JOIN WAREHOUSE.STOCK_ITEMS si ON si.STOCK_ITEM_ID = il.STOCK_ITEM_ID",
        "SELECT s.SUPPLIER_NAME, si.STOCK_ITEM_NAME FROM SALES.ITEM_SUPPLIER_LINK l JOIN PURCHASING.SUPPLIERS s ON"
                + " s.SUPPLIER_ID = l.SUPPLIER_ID JOIN WAREHOUSE.STOCK_ITEMS si ON si.STOCK_ITEM_ID = l.STOCK_ITEM_ID",
        "SELECT c.CUSTOMER_CATEGORY, COUNT(*) FROM SALES.CUSTOMERS c GROUP BY c.CUSTOMER_CATEGORY",
        "SELECT COUNT(*) FROM SALES.ORDERS o WHERE o.ORDER_DATE BETWEEN DATE '2025-06-01' AND DATE '2025-06-30'",
        "SELECT AVG(il.EXTENDED_PRICE) FROM SALES.INVOICE_LINES il WHERE il.QUANTITY > 5",
        "SELECT co.COLOR_NAME, COUNT(*) FROM WAREHOUSE.STOCK_ITEMS si JOIN WAREHOUSE.COLORS co ON co.COLOR_ID ="
                + " si.COLOR_ID GROUP BY co.COLOR_NAME",
        "SELECT si.BRAND, AVG(si.UNIT_PRICE) FROM WAREHOUSE.STOCK_ITEMS si GROUP BY si.BRAND",
        "SELECT c.CUSTOMER_NAME FROM SALES.CUSTOMERS c WHERE c.CUSTOMER_CATEGORY = 'Online' AND c.DELIVERY_CITY_ID"
                + " IS NOT NULL",
        "SELECT COUNT(DISTINCT i.CUSTOMER_ID) FROM SALES.INVOICES i WHERE i.INVOICE_DATE >= DATE '2026-01-01'",
        "SELECT ci.CITY_NAME, COUNT(*) FROM SALES.CUSTOMERS c JOIN APPLICATION.CITIES ci ON ci.CITY_ID ="
                + " c.DELIVERY_CITY_ID GROUP BY ci.CITY_NAME ORDER BY 2 DESC LIMIT 10",
        "SELECT SUM(il.EXTENDED_PRICE) FROM SALES.INVOICE_LINES il JOIN SALES.INVOICES i ON i.INVOICE_ID ="
                + " il.INVOICE_ID WHERE i.INVOICE_DATE BETWEEN DATE '2025-01-01' AND DATE '2025-12-31'",
        "SELECT o.SALESPERSON_ID, COUNT(*) FROM SALES.ORDERS o GROUP BY o.SALESPERSON_ID",
        "SELECT si.STOCK_ITEM_NAME, si.UNIT_PRICE FROM WAREHOUSE.STOCK_ITEMS si WHERE si.UNIT_PRICE > 100",
        "SELECT c.CUSTOMER_CATEGORY, SUM(il.EXTENDED_PRICE) FROM SALES.INVOICE_LINES il JOIN SALES.INVOICES i ON"
                + " i.INVOICE_ID = il.INVOICE_ID JOIN SALES.CUSTOMERS c ON c.CUSTOMER_ID = i.CUSTOMER_ID GROUP BY"
                + " c.CUSTOMER_CATEGORY",
        "SELECT COUNT(*) FROM SALES.ORDER_LINES ol WHERE ol.QUANTITY >= 10",
        "SELECT sp.STATE_PROVINCE_CODE, COUNT(*) FROM APPLICATION.CITIES ci JOIN APPLICATION.STATE_PROVINCES sp ON"
                + " sp.STATE_PROVINCE_ID = ci.STATE_PROVINCE_ID GROUP BY sp.STATE_PROVINCE_CODE",
    };

    private interface RowSource {
        void fill(List<Object[]> rows);
    }

    private static void batch(Connection c, String sql, RowSource source) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        source.fill(rows);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    ps.setObject(i + 1, row[i]);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
