// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Pflichtkonnektoren (FR-CON-02): PostgreSQL, Oracle, SQL Server, H2, chosen by JDBC URL scheme or,
 * for DataSource beans, by the product name.
 */
public final class Connectors {
    private Connectors() {}

    /** Builds a connector over an own read-only Hikari pool. */
    public static SourceDatabase open(ConnectionSpec spec) {
        if (spec.url() == null || spec.url().isBlank()) {
            throw new IllegalArgumentException("Connection " + spec.name() + " has no JDBC URL");
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(spec.url());
        if (spec.username() != null) {
            cfg.setUsername(spec.username());
        }
        if (spec.password() != null) {
            cfg.setPassword(spec.password());
        }
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(0);
        cfg.setReadOnly(true);
        cfg.setPoolName("ao-source-" + spec.name());
        cfg.setInitializationFailTimeout(-1);
        HikariDataSource ds = new HikariDataSource(cfg);
        return create(dialectOfUrl(spec.url()), spec.name(), ds, ds);
    }

    /** A connector on an existing DataSource bean (FR-NLC-01); the pool is not owned. */
    public static SourceDatabase wrap(String name, DataSource dataSource) {
        String product;
        try (var c = dataSource.getConnection()) {
            product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        } catch (java.sql.SQLException e) {
            throw new DbErrors.SourceQueryException(e);
        }
        String dialect = product.contains("postgres") ? "postgres"
                : product.contains("oracle") ? "oracle"
                : product.contains("microsoft") || product.contains("sql server") ? "tsql"
                : product.contains("h2") ? "h2"
                : "generic";
        return create(dialect, name, dataSource, null);
    }

    public static String dialectOfUrl(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:postgresql:")) {
            return "postgres";
        }
        if (u.startsWith("jdbc:oracle:")) {
            return "oracle";
        }
        if (u.startsWith("jdbc:sqlserver:")) {
            return "tsql";
        }
        if (u.startsWith("jdbc:h2:")) {
            return "h2";
        }
        throw new IllegalArgumentException("Unsupported JDBC URL scheme (supported: postgresql, oracle, sqlserver, h2): "
                + ConnectionSpec.redact(url));
    }

    private static SourceDatabase create(String dialect, String name, DataSource ds, AutoCloseable owned) {
        return switch (dialect) {
            case "postgres" -> new PostgresSource(name, ds, owned);
            case "oracle" -> new OracleSource(name, ds, owned);
            case "tsql" -> new SqlServerSource(name, ds, owned);
            case "h2" -> new H2Source(name, ds, owned);
            default -> throw new IllegalArgumentException("Unsupported database product for " + name);
        };
    }

    /** Database name from a JDBC URL ({@code jdbc:h2:mem:demo_sales} → {@code demo_sales}). */
    public static String nameFromUrl(String url) {
        String u = url.replaceFirst("(?i)^jdbc:", "");
        int semi = u.indexOf(';');
        int q = u.indexOf('?');
        int cut = semi < 0 ? q : q < 0 ? semi : Math.min(semi, q);
        if (cut >= 0) {
            u = u.substring(0, cut);
        }
        if (u.toLowerCase(Locale.ROOT).startsWith("sqlserver:")) {
            for (String part : url.split(";")) {
                if (part.toLowerCase(Locale.ROOT).startsWith("databasename=")) {
                    return part.substring("databasename=".length());
                }
            }
        }
        String[] parts = u.split("[/:@]");
        String last = parts[parts.length - 1];
        return last.isBlank() ? "database" : last;
    }

    /** {@code user}/{@code password} from URL parameters, as {@code CONNECTION_STRINGS} carry them. */
    public static ConnectionSpec fromConnectionString(String raw) {
        String s = raw.trim();
        String name = null;
        int eq = s.indexOf('=');
        if (eq > 0 && !s.substring(0, eq).contains(":")) {
            name = s.substring(0, eq).trim();
            s = s.substring(eq + 1).trim();
        }
        return new ConnectionSpec(name == null ? nameFromUrl(s) : name, s, null, null, null, List.of(), null, null);
    }

    static final Set<String> COMMON_SYSTEM = Set.of("INFORMATION_SCHEMA");

    static Set<String> merge(Set<String> a, Set<String> b) {
        java.util.HashSet<String> s = new java.util.HashSet<>(a);
        s.addAll(b);
        return s;
    }

    static Map<String, Object> history(Object time, Object text) {
        return Map.of("end_time", time, "query_text", text);
    }
}
