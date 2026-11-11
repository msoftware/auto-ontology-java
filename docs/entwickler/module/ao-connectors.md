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
| `ReadOnlySql` | Prüfung auf ein einzelnes `SELECT`/`WITH` |
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

`ReadOnlySql.violation(sql)` liefert `null`, wenn genau ein Statement vorliegt, das ein `SELECT`
(inkl. `WITH`) ist und weder `SELECT … INTO` noch `FOR UPDATE` enthält; sonst den Grund.
Abschließende Semikolons werden vorher entfernt. Das ist die Schutzgrenze für **alle** vom Agenten
ausgeführten Statements; zusätzlich laufen eigene Pools mit `readOnly = true`. Quell-DBs trotzdem
immer mit einem reinen Lesebenutzer anbinden.

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

## Tests

`ConnectorContract` (abstrakt) prüft Tabellen und Typen, Spalten mit Boolean-`is_nullable` und
Ordinalposition, PKs, FKs, Views, `sampleQuery` und die Read-only-Ausführung.

| Test | Engine | Lauf |
|---|---|---|
| `H2SourceTest` | H2 | Standard-Build |
| `H2UpperCaseSourceTest` | H2 ohne `DATABASE_TO_LOWER` | Standard-Build |
| `PostgresSourceTest` | eingebettetes PostgreSQL | Standard-Build |
| `OracleSourceIT` | `gvenzl/oracle-free` | `integrationTest` |
| `SqlServerSourceIT` | `mcr.microsoft.com/mssql/server` | `integrationTest` |

## Fallstricke

- **T-SQL `limit`:** `SELECT TOP n * FROM (… ORDER BY …) AS ao_limited` ist in SQL Server ungültig,
  wenn das innere SQL `ORDER BY` ohne `TOP`/`OFFSET` enthält. Betroffen ist jede Stelle, die
  `limit()` um Agent-SQL legt.
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
