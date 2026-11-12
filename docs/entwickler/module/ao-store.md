# Modul `ao-store`

Paket `de.jentsch.autoontology.store` · [Zurück zur Übersicht](../README.md)

## Zweck

Der **Metadaten-Store**: Schema per Liquibase und Repositories auf Springs `JdbcClient`. Hier liegen
Katalog, Ontologie, Governance, Chat-Verläufe, Laufzeiteinstellungen und die Kompilierungshistorie.
Bewusst **kein JPA** – analog zum „kein ORM“ des Originals (SQLAlchemy Core).

## Abhängigkeiten

`api`: `ao-common`, `spring-boot-starter-jdbc`, `spring-boot-starter-liquibase`.
Tests: H2, eingebettetes PostgreSQL (zonky), PostgreSQL-Treiber.

Der Store nutzt die **primäre `DataSource` des Hosts** (`spring.datasource.*`). Unterstützt sind
PostgreSQL (Standard) und H2 (`MODE=PostgreSQL`, Demo).

## Klassen

| Klasse | Aufgabe |
|---|---|
| `StoreAutoConfiguration` | registriert Liquibase-Bean, `StoreJdbc`, alle Repositories und `RuntimeSettings` |
| `AoSpringLiquibase` | `SpringLiquibase` mit eigenem Changelog und eigenen Changelog-Tabellen; lehnt persistente H2-Stores mit `DATABASE_TO_LOWER=TRUE` ab |
| `StoreJdbc` | dünne Hülle um `JdbcClient`: `list`, `one`, `update`, `count`, `column`; normalisiert Zeilen und Parameter |
| `CatalogRepository` | `catalog_*`, FKs, beobachtete Joins/Unions, `sql_query` und Verknüpfungen, generischer Node-Patch |
| `SemanticRepository` | Terms, ColumnAttributes, Semantic FKs, SqlAttributes, Custom/PQL Analyses, Reset der semantischen Schicht |
| `GovernanceRepository` | Tags und Tag-Ziele, Regeln, Zonen, Gruppen, verwendete Owner-Namen |
| `AppRepository` | `app_*`: Konversationen, Nachrichten, Custom Prompts, Konfiguration, Akronyme, Analytics |
| `HistoryRepository` | `semantic_compilation_history` (Start, Ende, letzter Erfolg/Fehler) |
| `RuntimeSettings` | typisierter Zugriff auf die Laufzeitschalter in `app_configurations` |

## Beans

| Bean | Typ | Bedingung |
|---|---|---|
| `aoLiquibase` | `SpringLiquibase` (`AoSpringLiquibase`) | `ao.store.liquibase.enabled` (Default `true`) |
| `aoStoreJdbc` | `StoreJdbc` | `@ConditionalOnMissingBean`; hängt von allen `SpringLiquibase`-Beans ab |
| `aoCatalogRepository`, `aoSemanticRepository`, `aoAppRepository`, `aoHistoryRepository`, `aoGovernanceRepository` | Repositories | `@ConditionalOnMissingBean` |
| `aoRuntimeSettings` | `RuntimeSettings` | `@ConditionalOnMissingBean` |

Die Auto-Konfiguration läuft **vor** `LiquibaseAutoConfiguration`. Weil es dann schon eine
`SpringLiquibase`-Bean gibt, tritt Boots eigene Liquibase-Konfiguration zurück. Ein Host mit eigenen
Migrationen muss deshalb seine eigene `SpringLiquibase`-Bean definieren (oder `aoLiquibase`
abschalten und `ao-changelog.yaml` in seinen Changelog einbinden).

## Schema

Master-Changelog `db/changelog/ao-changelog.yaml` definiert die Property `${text}` je Datenbank
(`TEXT` auf PostgreSQL, `CHARACTER VARYING` auf H2, `CLOB` auf Oracle, `NVARCHAR(MAX)` auf SQL
Server) und bindet `ao/001-schema.sql` ein. Changesets:

| Changeset | Inhalt |
|---|---|
| `ao:001-catalog` | `catalog_database`, `catalog_schema`, `catalog_table`, `catalog_column`, `column__foreign_key`, `column__join`, `column__union`, `table__join` |
| `ao:002-sql` | `sql_query`, `sql_query__table`, `sql_query__column` |
| `ao:003-semantic` | `term`, `table__term`, `column_attribute`, `column_attribute__term`, `column__has_attribute`, `column__semantic_fk`, `sql_attribute`, `sql_attribute__term`, `sql_attribute__sql`, `custom_analysis`, `custom_analysis__sql`, `pql_analysis`, `text_attribute`, `analysis`, `semantic_compilation_history` |
| `ao:004-governance` | `zone`, `zone_target`, `tag`, `rule`, `rule__tag`, `tag_target`, `user_group`, `user_group__member` |
| `ao:005-app` | `app_conversations`, `app_messages`, `app_prompts`, `app_configurations`, `app_acronyms`, `app_conversation_analytics` |
| `ao:006-vector-and-dataset` | `vector_record` (Persistenz des In-Memory-Vektorstores), `ao_dataset_link`, `ao_dataset_column_link` |
| `ao:007-search-indexes` | nur PostgreSQL, `failOnError:false`: `pg_trgm` und Trigram-GIN-Indizes auf `lower(name)` |

Liquibase-Tabellen heißen `AO_DATABASECHANGELOG` und `AO_DATABASECHANGELOGLOCK`.

**Konventionen** (aus `dal/schema.py` des Originals):

- IDs sind `VARCHAR(36)`-UUIDs.
- Containment über Parent-FKs mit `ON DELETE CASCADE` (Database → Schema → Table → Column). Das
  Löschen einer Datenbank räumt den ganzen Katalog ab.
- Assoziationstabellen haben genau ein `__` im Namen.
- `imported_id` auf importierbaren Entitäten, nicht eindeutig.
- Zertifizierungsflags `NOT NULL DEFAULT FALSE`.
- Audit-Spalten `modified`/`modified_by` werden nur bei menschlichen Änderungen gesetzt;
  Schreibvorgänge der Kompilierung lassen sie unberührt.
- `column_attribute.table_id` hat **keinen** FK und darf `''` sein (Merge-Key-Bestandteil).

## Implementierungsdetails

### `StoreJdbc`

```java
List<Map<String, Object>> rows = db.list("SELECT * FROM term WHERE source = :s", Map.of("s", "semantic"));
Optional<Map<String, Object>> row = db.one("SELECT * FROM term WHERE id = :id", Map.of("id", id));
int n = db.update("UPDATE term SET name = :n WHERE id = :id", Map.of("n", name, "id", id));
```

- Zeilen werden über `Rows.normalize` umgewandelt: Schlüssel klein, `Timestamp` → ISO-String.
- Parameter: `Instant` → `Timestamp`. Eine **leere Collection** wird durch `List.of("__none__")`
  ersetzt, weil `IN ()` ungültiges SQL ist; dieser Wert trifft nie eine ID.
- Named Parameters (`:name`); `Map.of` verbietet `null`-Werte – bei möglichen Nullwerten eine
  `HashMap` verwenden (so machen es die Repositories).
- Für SQL, das in PostgreSQL **und** H2 laufen muss, Standard-SQL verwenden (z. B.
  `FETCH FIRST 1 ROWS ONLY` statt `LIMIT`).

### Merge-Semantik der Ontologie

- `SemanticRepository.mergeTerm(name, description, synonyms, source, now)`: Schlüssel
  (`name`, `source`). Existiert der Term, wird er wiederverwendet, sonst angelegt. Audit-Spalten
  bleiben unberührt.
- `mergeColumnAttribute(...)`: Schlüssel (`name`, `source_column`, `term_name`, `table_id`,
  `source = 'semantic'`). Legt zusätzlich die Kanten `column_attribute__term` (PROPERTY_OF, genau
  ein Term je Attribut) und `column__has_attribute` an, falls noch nicht vorhanden.
- `addSemanticFk` / `setJoinDefault` / `semanticFkEdges` bedienen die Join-Pfad-Suche des Agenten.

### Reset

`SemanticRepository.deleteSemanticLayer(databaseId)`:

- `null`: löscht alle SqlAttributes, ColumnAttributes, Terms, Custom Analyses und PQL Analyses und
  setzt `is_junction_table` überall zurück.
- eine Datenbank: löscht Semantic FKs und ColumnAttributes ihrer Tabellen, die Zuordnungen
  `table__term` dieser Tabellen, ihre Custom Analyses und setzt `is_junction_table` ihrer Tabellen
  zurück. **PQL Analyses bleiben** (FR-SEM-08).
- **Geteilte Terms bleiben:** Terms werden über (`name`, `source`) zusammengeführt. Ein Term
  „Customer“ kann deshalb Tabellen in zwei Datenbanken beschreiben. Gelöscht (samt seiner
  SqlAttributes) wird ein Term nur, wenn er **ausschließlich** an Tabellen der zurückgesetzten
  Datenbank hängt; sonst verliert er nur die Zuordnungen zu dieser Datenbank.
- Rückgabe: die tatsächlich gelöschten Term-IDs.

```java
// Datenbank A und B haben je eine Tabelle CUSTOMERS, beide am Term "Customer"; A zusätzlich INVOICES/"Invoice"
List<String> deleted = semantic.deleteSemanticLayer(dbA);
// deleted == ["<id von Invoice>"];  "Customer" existiert weiter und zeigt nur noch auf B.CUSTOMERS
```

### ColumnAttributes entfernter Katalogobjekte

`column_attribute.table_id` hat keinen Fremdschlüssel. Verschwindet eine Tabelle oder Spalte aus der
Quelle, löscht die Kaskade zwar `column__has_attribute`, das Attribut selbst bliebe aber als Waise
zurück (mit Embedding im `semantic_layer`, das der Agent weiter als „wichtige Spalte“ fände). Dafür
gibt es zwei Methoden, die `CatalogWriter` vor dem Löschen der Katalogzeilen aufruft:

```java
List<String> ids = semantic.attributeIdsOf(removedTableIds, removedColumnIds);  // über table_id bzw. HAS_ATTRIBUTE
semantic.deleteAttributes(ids);                                                 // Kanten und Tags gehen per Kaskade mit
vectors.deleteByIds(VectorStore.SEMANTIC_LAYER, ids);                           // macht der IngestService
```

### Laufzeiteinstellungen

`RuntimeSettings` liest `app_configurations` bei **jedem** Aufruf (kein Cache), damit Änderungen
über die API sofort wirken.

| Methode | Schlüssel | Default |
|---|---|---|
| `visualizationEnabled()` | `visualization_enabled` | `true` |
| `distinctValueProbingEnabled()` | `distinct_value_probing_enabled` | `true` |
| `piiDetectionEnabled()` | `pii_detection_enabled` | `false` |
| `semanticCompilationEnabled()` | `semantic_compilation_enabled` | `false` |
| `sqlQueryTimeoutSeconds()` | `sql_query_timeout_seconds` | 30 (gültig 1–3600) |

Ungültige Werte fallen auf den Default zurück und werden als WARN geloggt. `seed(key, value)` setzt
einen Wert nur, wenn noch keiner existiert (Demo-Profile).

### Persistenter H2 mit `DATABASE_TO_LOWER`

`AoSpringLiquibase.createDatabase` prüft bei `jdbc:h2:` (nicht `mem:`) die H2-Einstellung
`DATABASE_TO_LOWER`. Ist sie `TRUE`, bricht der Start mit einer Erklärung ab: Liquibase sucht
seine Changelog-Tabelle in Großschrift und würde beim zweiten Start „table already exists“ melden.

## Erweiterungspunkte

- **Neue Tabelle oder Spalte:** neues Changeset (`--changeset ao:008-…`) am Ende von
  `001-schema.sql` oder eine neue Datei per `include` in `ao-changelog.yaml`. Bestehende Changesets
  nie ändern (Checksummen). `${text}` für Textspalten verwenden.
- **Neue Abfrage:** Methode im passenden Repository; SQL muss auf PostgreSQL und H2 laufen.
- **Neuer Laufzeitschalter:** Konstante und Getter in `RuntimeSettings`, Route in `AppController`.
- **Repository ersetzen:** eigene Bean desselben Typs (z. B. für einen anderen Store-Dialekt).

## Code-Beispiele

### Eine neue Spalte samt Repository-Methode

```sql
-- db/changelog/ao/001-schema.sql, ans Ende. Bestehende Changesets nie ändern (Checksummen).
--changeset ao:008-term-review
ALTER TABLE term ADD COLUMN reviewed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE term ADD COLUMN review_note ${text};
```

```java
// SemanticRepository.java
/** Marks a Term as reviewed; returns false when the id is unknown. */
public boolean markReviewed(String termId, String note, Instant now) {
    Map<String, Object> p = new HashMap<>();        // HashMap: note darf null sein, Map.of würde werfen
    p.put("id", termId);
    p.put("note", note);
    p.put("now", now);                              // Instant → Timestamp macht StoreJdbc
    return db.update("UPDATE term SET reviewed_at = :now, review_note = :note WHERE id = :id", p) == 1;
}

public List<Map<String, Object>> unreviewedTerms(int limit) {
    // Standard-SQL, das auf PostgreSQL und H2 läuft: FETCH FIRST statt LIMIT
    return db.list("SELECT id, name FROM term WHERE reviewed_at IS NULL ORDER BY LOWER(name)"
            + " FETCH FIRST " + limit + " ROWS ONLY");
}
```

`limit` ist hier ein `int` aus dem eigenen Code. Werte aus Requests gehören **immer** in benannte
Parameter; für `OFFSET`/`FETCH` vorher mit `Math.max(0, …)` begrenzen, sonst wird aus `?skip=-1`
ein SQL-Fehler und damit ein 500.

### IN-Listen und optionale Filter

```java
public List<Map<String, Object>> termsByIds(Collection<String> ids) {
    if (ids.isEmpty()) {
        return List.of();                           // spart die Abfrage; StoreJdbc würde "__none__" einsetzen
    }
    return db.list("SELECT * FROM term WHERE id IN (:ids)", Map.of("ids", ids));
}

Map<String, Object> p = new HashMap<>();
String where = "";
if (search != null && !search.isBlank()) {
    where = " WHERE LOWER(name) LIKE :q";
    p.put("q", "%" + search.toLowerCase(Locale.ROOT) + "%");
}
List<Map<String, Object>> rows = db.list("SELECT id, name FROM term" + where, p);
```

### Einen Laufzeitschalter ergänzen

```java
// RuntimeSettings.java
public static final String VALUE_REPAIR = "value_repair_enabled";

public boolean valueRepairEnabled() {
    return flag(VALUE_REPAIR, true);                // Default, wenn kein Eintrag existiert
}
```

```java
// AppController.java – Route und Schlüssel in beiden @*Mapping-Listen und den switch-Blöcken ergänzen
case "value-repair" -> Map.of("enabled", settings.valueRepairEnabled());
// ...
case "value-repair" -> RuntimeSettings.VALUE_REPAIR;
```

```bash
curl -X PUT localhost:8080/api/configurations/value-repair -H 'Content-Type: application/json' -d '{"enabled": false}'
```

### Ein Repository ohne Spring testen

```java
JdbcDataSource ds = new JdbcDataSource();
ds.setURL("jdbc:h2:mem:t" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
SpringLiquibase lb = new AoSpringLiquibase();       // das echte Schema, dieselben Changesets wie im Betrieb
lb.setDataSource(ds);
lb.setResourceLoader(new DefaultResourceLoader());
lb.afterPropertiesSet();
StoreJdbc db = new StoreJdbc(JdbcClient.create(ds));
SemanticRepository semantic = new SemanticRepository(db);
```

Für Tests, die auf beiden Stores laufen sollen, das Muster aus `StoreSchemaTest` übernehmen
(`@ParameterizedTest` + `@MethodSource("stores")` mit H2 und eingebettetem PostgreSQL).

## Tests

`StoreSchemaTest` (parametrisiert über H2 und eingebettetes PostgreSQL):

- `migrationIsRepeatable` – zweiter Liquibase-Lauf ändert nichts,
- `catalogAndSemanticRoundTrip` – Katalog, Terms, Attribute schreiben und lesen,
- `resetOfOneDatabaseKeepsSharedTerms` – der Reset einer Datenbank löscht nur ihre eigenen Terms,
  räumt ihre ColumnAttributes ab und setzt `is_junction_table` zurück,
- `appTablesAndSettings` – App-Tabellen und `RuntimeSettings`-Defaults.

## Fallstricke

- `CatalogRepository.patchNode(table, id, values)` und `SemanticRepository.patch(...)` bauen
  `UPDATE <table> SET <key> = :key …` aus den Map-Schlüsseln. **Tabellenname und Schlüssel dürfen nie
  aus Benutzereingaben stammen**; die Controller setzen sie aus festen Listen.
- `Map.of(...)` mit `null`-Werten wirft `NullPointerException` – das ist der häufigste Fehler bei neuen
  Repository-Methoden.
- Boolesche Spalten sind echte `BOOLEAN`; beim Schreiben `Boolean.TRUE.equals(...)` statt String
  verwenden.
- Oracle und SQL Server als Store sind nicht getestet (P3), obwohl `${text}` dafür vorbereitet ist.

## Anforderungsbezug

FR-DB-01 bis FR-DB-05, FR-CFG-02, FR-SEM-07 (Defaults der Schalter, Historie), FR-SEM-08 (Reset),
FR-DEMO-01 (`vector_record`), FR-NLC-02 (`ao_dataset_*`).
