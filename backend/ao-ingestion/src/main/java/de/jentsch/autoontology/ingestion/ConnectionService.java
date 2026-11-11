// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.connectors.ConnectionSpec;
import de.jentsch.autoontology.connectors.Connectors;
import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.store.CatalogRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;

/**
 * Connection sources (FR-CON-04, docs/connections.md): connections created in the UI (credentials
 * as JSON in {@code catalog_database.connection}) or configured ones ({@code CONNECTION_STRINGS},
 * {@code ao.ingest.connections}, DataSource beans). The precedence is deliberately asymmetric:
 * answering prefers UI connections, ingestion prefers configured ones.
 */
public class ConnectionService {
    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);
    private static final Set<String> SECRET_KEYS = Set.of("password", "pwd", "secret", "token", "api_key", "private_key");

    private final AoProperties props;
    private final CatalogRepository catalog;
    private final BeanFactory beans;
    private final AoTime time;
    private final Map<String, SourceDatabase> open = new ConcurrentHashMap<>();

    public ConnectionService(AoProperties props, CatalogRepository catalog, BeanFactory beans, AoTime time) {
        this.props = props;
        this.catalog = catalog;
        this.beans = beans;
        this.time = time;
    }

    // ------------------------------------------------------------------ sources

    /** Connections from configuration: CONNECTION_STRINGS plus {@code ao.ingest.connections}. */
    public List<ConnectionSpec> configuredConnections() {
        List<ConnectionSpec> out = new ArrayList<>();
        String raw = props.ingest().connectionStrings();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split("[,\\n]")) {
                if (!part.isBlank()) {
                    out.add(Connectors.fromConnectionString(part));
                }
            }
        }
        if (props.ingest().connections() != null) {
            for (AoProperties.ConnectionConfig c : props.ingest().connections()) {
                String name = c.name() != null ? c.name()
                        : c.url() != null ? Connectors.nameFromUrl(c.url()) : c.datasourceBean();
                out.add(new ConnectionSpec(name, c.url(), null, null, c.datasourceBean(), c.schemas(),
                        c.tableAllowRegex(), c.tableDenyRegex()));
            }
        }
        return out;
    }

    /** {@code GET /api/connections/source}: whether connections come from the environment. */
    public boolean isEnvSource() {
        return !configuredConnections().isEmpty();
    }

    /** Connections created in the UI. */
    public List<ConnectionSpec> uiConnections() {
        List<ConnectionSpec> out = new ArrayList<>();
        for (Map<String, Object> db : catalog.databases()) {
            String name = (String) db.get("name");
            catalog.databaseByName(name).map(r -> (String) r.get("connection")).filter(c -> c != null && !c.isBlank())
                    .ifPresent(json -> out.add(fromJson(name, Json.readMap(json))));
        }
        return out;
    }

    /** Answering: UI connections when there are any, otherwise the configured ones. */
    public List<ConnectionSpec> answeringConnections() {
        List<ConnectionSpec> ui = uiConnections();
        return ui.isEmpty() ? configuredConnections() : ui;
    }

    /** Ingestion: configured connections when set, otherwise the UI ones. */
    public List<ConnectionSpec> ingestConnections() {
        List<ConnectionSpec> env = configuredConnections();
        return env.isEmpty() ? uiConnections() : env;
    }

    // ------------------------------------------------------------------ connectors

    /** The connector for a database name, opened lazily and cached. */
    public SourceDatabase connector(ConnectionSpec spec) {
        return open.computeIfAbsent(spec.name(), n -> openConnector(spec));
    }

    /** Connectors used to answer questions (chat runtime). */
    public List<SourceDatabase> answeringConnectors() {
        List<SourceDatabase> out = new ArrayList<>();
        for (ConnectionSpec spec : answeringConnections()) {
            try {
                out.add(connector(spec));
            } catch (RuntimeException e) {
                log.warn("Connector {} could not be opened: {}", spec.name(), e.getMessage());
            }
        }
        return out;
    }

    public SourceDatabase openConnector(ConnectionSpec spec) {
        if (spec.datasourceBean() != null && !spec.datasourceBean().isBlank()) {
            DataSource ds = beans.getBean(spec.datasourceBean(), DataSource.class);
            return Connectors.wrap(spec.name(), ds);
        }
        return Connectors.open(spec);
    }

    /** Closes cached connectors so that the next call reopens them (after create/delete, FR-ING-03). */
    public void refresh() {
        open.values().forEach(SourceDatabase::close);
        open.clear();
    }

    // ------------------------------------------------------------------ UI connections

    /** Parses the UI connection object (URL form or type/host/port/database form). */
    @SuppressWarnings("unchecked")
    public ConnectionSpec fromJson(String fallbackName, Map<String, Object> c) {
        String url = str(c, "url");
        if (url == null) {
            url = str(c, "jdbc_url");
        }
        if (url == null && str(c, "host") != null) {
            url = buildUrl(c);
        }
        String name = str(c, "database_name");
        if (name == null) {
            name = str(c, "name");
        }
        if (name == null) {
            name = fallbackName != null ? fallbackName : url != null ? Connectors.nameFromUrl(url) : str(c, "datasource_bean");
        }
        Object schemas = c.get("schemas");
        List<String> schemaList = schemas instanceof List<?> l ? l.stream().map(String::valueOf).toList()
                : schemas instanceof String s && !s.isBlank() ? List.of(s.split("\\s*,\\s*")) : List.of();
        String user = str(c, "username");
        if (user == null) {
            user = str(c, "user");
        }
        return new ConnectionSpec(name, url, user, str(c, "password"), str(c, "datasource_bean"), schemaList,
                str(c, "table_allow_regex"), str(c, "table_deny_regex"));
    }

    private static String buildUrl(Map<String, Object> c) {
        String type = String.valueOf(c.getOrDefault("type", "postgres")).toLowerCase(Locale.ROOT);
        String host = str(c, "host");
        String port = str(c, "port");
        String db = str(c, "database");
        return switch (type) {
            case "oracle" -> "jdbc:oracle:thin:@//" + host + ":" + (port == null ? "1521" : port) + "/" + db;
            case "sqlserver", "mssql", "tsql" -> "jdbc:sqlserver://" + host + ":" + (port == null ? "1433" : port)
                    + ";databaseName=" + db + ";encrypt=true;trustServerCertificate=true";
            case "h2" -> "jdbc:h2:" + db;
            default -> "jdbc:postgresql://" + host + ":" + (port == null ? "5432" : port) + "/" + db;
        };
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    /** The connection without credentials (FR-CON-06, NFR-SEC-06). */
    public static Map<String, Object> publicConnection(String databaseName, Map<String, Object> connection) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (connection != null) {
            connection.forEach((k, v) -> {
                if (!SECRET_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
                    c.put(k, "url".equals(k) || "jdbc_url".equals(k) ? ConnectionSpec.redact(String.valueOf(v)) : v);
                }
            });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("database_name", databaseName);
        out.put("connection", c);
        return out;
    }

    /** Lists UI connections plus configured ones, without credentials. */
    public List<Map<String, Object>> publicConnections() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> db : catalog.databases()) {
            String name = (String) db.get("name");
            String json = catalog.databaseByName(name).map(r -> (String) r.get("connection")).orElse(null);
            if (json != null && !json.isBlank()) {
                out.add(publicConnection(name, Json.readMap(json)));
            }
        }
        for (ConnectionSpec spec : configuredConnections()) {
            if (out.stream().noneMatch(m -> spec.name().equals(m.get("database_name")))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("url", spec.redactedUrl());
                c.put("datasource_bean", spec.datasourceBean());
                c.put("schemas", spec.schemas());
                c.put("source", "env");
                out.add(publicConnection(spec.name(), c));
            }
        }
        return out;
    }

    /** Stores a UI connection; returns the database name. */
    public String save(ConnectionSpec spec, Map<String, Object> rawConnection) {
        String dialect = spec.url() != null ? Connectors.dialectOfUrl(spec.url()) : null;
        String id = catalog.upsertDatabase(spec.name(), dialect, rawConnection, time.now());
        catalog.setConnection(id, rawConnection);
        refresh();
        return spec.name();
    }
}
