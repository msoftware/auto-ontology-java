# Modul `ao-connectors`

Paket `de.jentsch.autoontology.connectors` · [Zurück zur Übersicht](../README.md)

## Zweck

Zugriff auf die **Quell-Datenbanken**, deren Metadaten eingelesen und auf denen Agent-SQL ausgeführt
wird. Ein gemeinsamer Vertrag (`SourceDatabase`) für PostgreSQL, Oracle, SQL Server und H2, die
Read-only-Prüfung für Agent-SQL, Tabellenfilter und Fehlerklassifikation.

## Abhängigkeiten

`api`: `ao-common`, `spring-boot-starter-jdbc` (HikariCP), JSqlParser 5.4.
`implementation`: JDBC-Treiber H2, PostgreSQL, Oracle (`ojdbc11`), SQL Server (`mssql-jdbc`).
Tests: eingebettetes PostgreSQL, Testcontainers Oracle Free und SQL Server.

Das Modul hat keine Auto-Konfiguration und keine Beans; Konnektoren erzeugt `ConnectionService`
in `ao-ingestion`.

## Klassen

| Klasse | Aufgabe |
|---|---|
| `SourceDatabase` | Vertrag aller Konnektoren (Vorbild `connectors/base.py::SQLDatabase`) |
| `JdbcSourceDatabase` | gemeinsame Implementierung: Ausführung, Metadaten über `DatabaseMetaData`, Quoting, `LIMIT` |
| `PostgresSource` | Dialekt `postgres`, Historie aus `pg_stat_activity` |
| `OracleSource` | Dialekt `oracle`, `FETCH FIRST`, Systemschemas, Historie aus `v$sql` |
| `SqlServerSource` | Dialekt `tsql`, `[…]`-Quoting, `TOP n`, Kommentare aus `MS_Description`, Historie aus dem Query Store |
| `H2Source` | Dialekt `h2`, Demo-Historie aus `DEMO_META.QUERY_HISTORY` |
| `Connectors` | Fabrik: aus JDBC-URL (eigener Hikari-Pool) oder `DataSource`-Bean; Parser für `CONNECTION_STRINGS` |
| `ConnectionSpec` | Verbindungsbeschreibung (URL, Credentials, Bean-Name, Scoping) mit maskiertem `toString()` |
| `TableFilter` | Schema-Allowlist und Allow-/Deny-Regex |
| `ReadOnlySql` | Prüfung auf ein einzelnes, rein lesendes `SELECT`/`WITH` (rekursiv über CTEs, Mengenoperationen, Unterabfragen im `FROM`) |
| `TabularResult` | Spaltennamen (eindeutig gemacht), JDBC-Typen, Zeilen, `truncated` |
| `DbErrors` | Klassifikation in `TIMEOUT`, `CONNECTION`, `SYNTAX`, `PERMISSION`, `OTHER`; `SourceQueryException` |

## Der Vertrag

| Methode | Rückgabe / Verhalten |
|---|---|
| `execute(sql, params, timeoutSeconds, maxRows)` | `TabularResult`; Timeout über `setQueryTimeout`, Limit über `setMaxRows(maxRows + 1)`, Abbruch über das gebundene `CancellationToken` |
| `getTables()` | `table_schema`, `table_name`, `table_type` ∈ {`base table`, `view`, `materialized view`} |
| `getColumns()` | `table_schema`, `table_name`, `column_name`, `data_type`, `is_nullable` (**Boolean**), `ordinal_position` |
| `getViews()` | `table_schema`, `table_name`, `view_definition` |
| `getPks()` | `table_schema`, `table_name`, `column_name`, `ordinal_position` |
| `getFks()` | `table_schema`, `table_name`, `column_name`, `referenced_schema`, `referenced_table`, `referenced_column` |
| `getQueries(hours)` / `getQueries()` | `end_time`, `query_text`; Default 24 h; ohne Rechte oder Feature leer |
| `getColumnComments()` | `table_schema`, `table_name`, `column_name` (null = Tabellenkommentar), `comment` |
| `dialect()` | `postgres`, `oracle`, `tsql`, `h2` – steuert Prompt-Regeln und Parser |
| `databaseName()` | Schlüssel im Katalog (`catalog_database.name`) |
| `qualify(schema, table)`, `quote(id)` | dialektgerecht gequotete Namen |
| `sampleQuery(schema, table, n)` | Stichprobe für das Profiling |
| `limit(sql, n)` | Zeilenbegrenzung um ein beliebiges `SELECT` |
| `listSchemas()` | sichtbare Schemas (Verbindungstest im Wizard) |
| `close()` | schließt den Pool, wenn der Konnektor ihn besitzt |

## Implementierungsdetails

### Erzeugung

```java
SourceDatabase db = Connectors.open(spec);                     // eigener Hikari-Pool aus spec.url()
SourceDatabase db = Connectors.wrap("analytics", dataSource);  // vorhandene DataSource, Pool gehört dem Host
ConnectionSpec spec = Connectors.fromConnectionString("sales=jdbc:postgresql://db/sales?user=ro&password=…");
```

- `open` erstellt einen Hikari-Pool mit `maximumPoolSize = 4`, `minimumIdle = 0`, `readOnly = true`
  und `initializationFailTimeout = -1` (der Start scheitert nicht an einer gerade nicht erreichbaren
  Quelle). Der Dialekt kommt aus dem URL-Schema (`dialectOfUrl`); andere Schemata werfen eine
  `IllegalArgumentException` mit maskierter URL.
- `wrap` bestimmt den Dialekt aus `getDatabaseProductName()` und schließt den Pool beim `close()`
  **nicht**. So erreicht Auto-Ontology eine In-Memory-H2 im selben Prozess (Dashboard-Demo).
- `fromConnectionString` akzeptiert `name=url` oder nur `url`; ohne Namen wird er aus der URL
  abgeleitet (`nameFromUrl`, bei SQL Server aus `databaseName=`).

### Metadaten

`JdbcSourceDatabase` liest alle Metadaten über `java.sql.DatabaseMetaData` (`getTables`,
`getColumns`, `getPrimaryKeys`, `getImportedKeys`, `REMARKS`) – ein Codepfad für alle Engines, der
intern dieselben Systemviews nutzt wie das Original. Unterklassen liefern:

- `systemSchemas()` – Schemas, die nie in den Katalog kommen (z. B. `INFORMATION_SCHEMA`,
  `PG_CATALOG`, ~40 Oracle-Systembenutzer, SQL-Server-Rollen),
- optional `visibleSchema()` für Präfixe (`pg_temp*`, `APEX_*`),
- `dialect()`, und je nach Engine `limit`, `sampleQuery`, `quote`, `getQueries`,
  `getColumnComments`.

`canonicalType` bildet JDBC-Tabellentypen auf die drei kanonischen Werte ab.

### Ausführung

- Spaltennamen werden eindeutig gemacht (`name`, `name_2`, …), wie `sql_execution.py`.
- Werte werden JSON-freundlich normalisiert (Zeitwerte, `BigDecimal`, LOBs, Arrays).
- Ist ein `CancellationToken` an den Thread gebunden, registriert `execute` für die Laufzeit des
  Statements `ps.cancel()` als Hook. Nach einem Abbruch wird `CancelledException` statt eines
  SQL-Fehlers geworfen.
- SQL-Fehler werden zu `DbErrors.SourceQueryException` mit `kind()`; der Agent nutzt die Kategorie
  in der Fehlermeldung an die Rekonstruktion.

### Quoting

`quote(id)` lässt einen Bezeichner ungequotet, wenn er der Faltung der Datenbank entspricht
(PostgreSQL und H2 mit `DATABASE_TO_LOWER` → klein, sonst groß), nur aus `[A-Za-z0-9_]` besteht und
kein reserviertes Wort ist; sonst `"…"`. SQL Server quotet immer mit `[…]`. Die Faltung wird einmal
über `DatabaseMetaData.storesLowerCaseIdentifiers()` ermittelt (Oracle fest groß, PostgreSQL fest
klein).

Die Liste der reservierten Wörter (`JdbcSourceDatabase.RESERVED`) umfasst die gängigen
SQL-Schlüsselwörter (`ORDER`, `GROUP`, `USER`, `END`, `DESC`, `COMMENT`, `LEVEL`, `SIZE`, `NUMBER` …).
Sie ist bewusst großzügig: Einen Bezeichner zu quoten, der schon in der Faltung der Datenbank
geschrieben ist, ändert seine Bedeutung nie – ein nicht gequotetes Schlüsselwort dagegen bricht das
Statement.

```java
H2Source h2 = …;                    // Standard-H2: faltet nach GROSS
h2.quote("CUSTOMERS")               // CUSTOMERS            – passt zur Faltung, kein Schlüsselwort
h2.quote("ORDER")                   // "ORDER"              – Schlüsselwort
h2.quote("customerName")            // "customerName"       – gemischte Schreibweise bleibt nur gequotet erhalten
h2.qualify("SALES", "ORDER LINES")  // SALES."ORDER LINES"
sqlServer.quote("Order")            // [Order]
```

### Limit je Dialekt

| Dialekt | `sampleQuery` | `limit(sql, n)` |
|---|---|---|
| PostgreSQL, H2 | `SELECT * FROM t LIMIT n` | `sql LIMIT n` |
| Oracle | `SELECT * FROM t FETCH FIRST n ROWS ONLY` | `SELECT * FROM (sql) FETCH FIRST n ROWS ONLY` |
| SQL Server | `SELECT TOP n * FROM t` | `SELECT TOP n * FROM (sql) AS ao_limited` |

### Query-Historie

| Engine | Quelle | Voraussetzung |
|---|---|---|
| PostgreSQL | `pg_stat_activity` (`state_change` als `end_time`, nur `client backend`) | keine; sieht nur den jeweils letzten Befehl je Verbindung |
| Oracle | `v$sql` (`command_type = 3`, ohne `SYS`/`SYSTEM`) | Leserechte auf `v$sql` |
| SQL Server | Query Store (`sys.query_store_*`) | Query Store aktiv |
| H2 | `DEMO_META.QUERY_HISTORY(END_TIME, QUERY_TEXT)` | Tabelle vorhanden (Demo) |

`optionalHistory` verschluckt fehlende Rechte oder Features und liefert eine leere Liste.

### Read-only-Prüfung

`ReadOnlySql.violation(sql)` liefert `null`, wenn genau ein Statement vorliegt, das **rein lesend**
ist; sonst den Grund als Text. Abschließende Semikolons werden vorher entfernt. Das ist die
Schutzgrenze für **alle** vom Agenten ausgeführten Statements; zusätzlich laufen eigene Pools mit
`readOnly = true`. Quell-DBs trotzdem immer mit einem reinen Lesebenutzer anbinden.

Die Prüfung läuft **rekursiv** über das ganze Statement. Der Typ des obersten Statements allein
beweist nichts: JSqlParser meldet auch ein schreibendes CTE als gewöhnliches `Select`.

| Statement | Ergebnis von `violation` |
|---|---|
| `SELECT a FROM t` · `WITH x AS (SELECT …) SELECT …` · `… UNION ALL …` | `null` (erlaubt) |
| `WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d` (ebenso `INSERT`/`UPDATE`) | `data-modifying statements in WITH are not allowed` |
| `SELECT … FOR UPDATE` – auch in Klammern, in einem CTE oder in einer Unterabfrage im `FROM` | `SELECT ... FOR UPDATE is not allowed` |
| `SELECT a INTO copy FROM t` | `SELECT INTO is not allowed` |
| `SELECT 1; DROP TABLE t` | `only a single statement is allowed` |
| `DELETE FROM t`, `CREATE …`, `CALL …` | `only SELECT/WITH statements are allowed` |
| nicht parsbar | `SQL could not be parsed: …` |

Geprüft werden CTEs, Mengenoperationen (`UNION` …), geklammerte Selects und abgeleitete Tabellen
in `FROM`/`JOIN`. **Nicht** erkennbar sind Funktionen mit Seiteneffekten
(`SELECT pg_terminate_backend(…)`, `nextval(…)`): Dagegen hilft nur der Lesebenutzer.

### Tabellenfilter

`TableFilter.allows(schema, table)`: Schema in der Allowlist (case-insensitiv; leer = alle), Deny-
Regex trifft nicht, Allow-Regex trifft (leer = alle). Abgleich per `find()` auf dem
**unqualifizierten** Tabellennamen, case-sensitiv. Deny gewinnt.

## Erweiterungspunkte

**Neue Engine** (z. B. MySQL):

1. `MySqlSource extends JdbcSourceDatabase` mit `systemSchemas()`, `dialect()` und den nötigen
   Overrides (`quote`, `limit`, `sampleQuery`, `getQueries`).
2. In `Connectors.dialectOfUrl`, `wrap` und `create` registrieren; Treiber in
   `gradle/libs.versions.toml` und `build.gradle.kts`.
3. Dialektregeln `ao-agent/src/main/resources/prompts/dialect_mysql.txt` (existiert bereits) und
   ggf. `AgentPrompts.dialectRules` anpassen.
4. Test von `ConnectorContract` ableiten (Schema `shop` mit `customers`, `orders`, `v_orders`);
   Docker-basiert als `*IT` mit `@Tag("integration")`.

## Code-Beispiele

### Einen Konnektor öffnen und abfragen

```java
ConnectionSpec spec = new ConnectionSpec("sales", "jdbc:postgresql://db:5432/sales", "ao_reader", secret,
        null,                                   // datasourceBean
        List.of("public"),                      // Schema-Allowlist (leer = alle)
        null, "^(tmp_|bak_)");                  // Allow-/Deny-Regex auf dem Tabellennamen

try (SourceDatabase db = Connectors.open(spec)) {            // eigener Hikari-Pool, readOnly = true
    TabularResult r = db.execute(
            "SELECT region, SUM(amount) AS revenue FROM public.orders WHERE order_date >= ? GROUP BY region",
            List.of(java.sql.Date.valueOf("2025-01-01")),    // Parameter als ? – nie in den Text bauen
            30,                                               // Timeout in Sekunden
            1000);                                            // Zeilenobergrenze
    r.columns();        // [region, revenue]        – eindeutig gemacht (name, name_2, …)
    r.jdbcTypes();      // [12, 2]                  – java.sql.Types
    r.asMaps();         // [{region=North, revenue=1234.5}, …]
    r.truncated();      // true, wenn es mehr als 1000 Zeilen gab
} catch (DbErrors.SourceQueryException e) {
    log.warn("{}: {}", e.kind(), e.getMessage());             // TIMEOUT | CONNECTION | SYNTAX | PERMISSION | OTHER
}
```

Im laufenden System öffnet man Konnektoren nicht selbst, sondern holt sie über
`ConnectionService.connector(spec)` (gecacht) bzw. `answeringConnectors()`.

### Vor dem Ausführen prüfen

```java
String violation = ReadOnlySql.violation(sql);
if (violation != null) {
    throw ApiException.unprocessable(violation);              // 422 mit dem Grund
}
TabularResult r = db.execute(ReadOnlySql.stripTrailingSemicolons(sql), List.of(), timeout, maxRows);
```

### Eigenes SQL dialektneutral bauen

```java
// Bezeichner immer über quote/qualify, Zeilenbegrenzung immer über limit – nie " LIMIT n" anhängen
String sql = db.limit("SELECT DISTINCT " + db.quote(column) + " FROM " + db.qualify(schema, table)
        + " WHERE " + db.quote(column) + " IS NOT NULL", 25);
// PostgreSQL/H2: SELECT DISTINCT status FROM sales.orders WHERE status IS NOT NULL LIMIT 25
// Oracle:        SELECT * FROM (SELECT DISTINCT STATUS FROM SALES.ORDERS WHERE …) FETCH FIRST 25 ROWS ONLY
// SQL Server:    SELECT TOP 25 * FROM (SELECT DISTINCT [status] FROM [sales].[orders] WHERE …) AS ao_limited
```

`limit()` ist für **selbst gebautes** SQL ohne `ORDER BY` gedacht (Stichproben, DISTINCT-Proben).
SQL des Agenten wird nie umschlossen: Dort begrenzt `execute(…, maxRows)` über
`Statement.setMaxRows`.

### Eine neue Engine

```java
/** MySQL: Backtick-Quoting, LIMIT, Schema = Datenbank. */
public class MySqlSource extends JdbcSourceDatabase {
    public MySqlSource(String name, DataSource ds, AutoCloseable owned) {
        super(name, ds, owned);
    }

    @Override
    protected Set<String> systemSchemas() {
        return Set.of("INFORMATION_SCHEMA", "MYSQL", "PERFORMANCE_SCHEMA", "SYS");
    }

    @Override
    public String dialect() {
        return "mysql";
    }

    @Override
    public String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public List<Map<String, Object>> getQueries(int hours) {
        return List.of();                       // keine Historie: die SQL Attributes aus Abfragen entfallen
    }
}
```

Dazu in `Connectors`: `dialectOfUrl` (`jdbc:mysql:` → `"mysql"`), `wrap` (Produktname) und `create`
(`case "mysql" -> new MySqlSource(…)`), der Treiber in `libs.versions.toml`, und ein Test:

```java
class MySqlSourceIT extends ConnectorContract {             // @Tag("integration"), Testcontainers
    @Override
    protected SourceDatabase source() { return new MySqlSource("shop", dataSource, null); }

    @Override
    protected String expectedDialect() { return "mysql"; }
}
```

## Tests

`ConnectorContract` (abstrakt) prüft Tabellen und Typen, Spalten mit Boolean-`is_nullable` und
Ordinalposition, PKs, FKs, Views, `sampleQuery` und die Read-only-Ausführung.

`ReadOnlySqlTest` prüft die Read-only-Regel (erlaubte Abfragen, schreibende CTEs, `FOR UPDATE` auf
jeder Ebene, `SELECT INTO`, mehrere Statements) und `Connectors.nameFromUrl` für alle Engines.

| Test | Engine | Lauf |
|---|---|---|
| `H2SourceTest` | H2 | Standard-Build |
| `H2UpperCaseSourceTest` | H2 ohne `DATABASE_TO_LOWER` | Standard-Build |
| `PostgresSourceTest` | eingebettetes PostgreSQL | Standard-Build |
| `OracleSourceIT` | `gvenzl/oracle-free` | `integrationTest` |
| `SqlServerSourceIT` | `mcr.microsoft.com/mssql/server` | `integrationTest` |

## Fallstricke

- **T-SQL `limit`:** `SELECT TOP n * FROM (… ORDER BY …) AS ao_limited` ist in SQL Server ungültig,
  wenn das innere SQL `ORDER BY` ohne `TOP`/`OFFSET` enthält. `limit()` deshalb nur um eigenes SQL
  ohne `ORDER BY` legen (so wird es heute überall verwendet); Agent-SQL wird über `setMaxRows`
  begrenzt.
- **Read-only ist eine Syntaxprüfung.** Sie erkennt schreibende Statements und Sperren, nicht aber
  Funktionen mit Seiteneffekten. H2 setzt `readOnly` am Pool zudem nicht durch.
- `wrap` auf einer `DataSource` eines nicht unterstützten Produkts wirft
  `IllegalArgumentException("Unsupported database product …")`.
- Bei `wrap` gibt es keinen `readOnly`-Pool; die Read-only-Prüfung des Agenten ist dann die einzige
  technische Schranke. In der Dashboard-Demo sollte die `DataSource` deshalb einem Lesebenutzer
  gehören.
- Historienabfragen bauen `hours` per String-Konkatenation ein (nur interne Integer-Werte).
- `foldsToLower()` wird pro Konnektor gecacht; ändert sich die H2-Einstellung zur Laufzeit, muss der
  Konnektor neu erzeugt werden (`ConnectionService.refresh()`).

## Anforderungsbezug

FR-CON-01 bis FR-CON-06, FR-DEMO-03, FR-NLC-01 (DataSource-Bean, `DATABASE_TO_LOWER`),
FR-AGT-04 (Limit), NFR-SEC-01/02, NFR-TST-02.
