# Modul `ao-ingestion`

Paket `de.jentsch.autoontology.ingestion` · [Zurück zur Übersicht](../README.md)

## Zweck

Bringt den Katalog der Quell-Datenbanken in den Store und in den Vektorstore: Verbindungen
verwalten, Metadaten extrahieren, gegen den Bestand diffen, Query-Historie parsen, Tabellen und
Spalten einbetten, periodisch wiederholen. Dazu die Governance-Dienste, die Ingest und API teilen:
PII-Erkennung, globale Suche und Regeln.

Vorbild im Original: `ingestion_service/ingest.py` (`run_ingest`), `catalog/*`,
`utils/embedding_rows.py`, `pii_detection/`, `server/search/`, `server/rules/`.

## Abhängigkeiten

`api`: `ao-store`, `ao-vector`, `ao-llm`, `ao-connectors`. Tests: H2.

## Klassen

| Klasse | Aufgabe |
|---|---|
| `IngestionAutoConfiguration` | Beans des Moduls; hängt PII und Regel-Replay als Stages an den `IngestService` |
| `ConnectionService` | Verbindungsquellen (UI, `CONNECTION_STRINGS`, `ao.ingest.connections`, DataSource-Beans), Präzedenz, Konnektor-Cache, öffentliche Darstellung ohne Credentials |
| `IngestService` | ein Ingest-Lauf je Verbindung (serialisiert), Stage-Hooks, Löschen einer Datenbank |
| `IngestScheduler` | `SmartLifecycle`: Lauf beim Start und danach im Intervall, manueller Trigger |
| `CatalogExtractor` | liest Tabellen, Spalten, Views, PKs, FKs, Kommentare, Historie; wendet `TableFilter` an; Vollständigkeitsprüfung |
| `CatalogWriter` | Diff und Schreiben des Katalogs in einer Transaktion |
| `QueryHistoryParser` | JSqlParser-basiertes Parsen beobachteter Statements: Tabellen, Spalten, Equi-Joins, Unions, Hash |
| `DataLayerEmbedder` | baut die Embedding-Zeilen des Data Layers und schreibt sie in `data_objects_layer` |
| `EmbeddingTexts` | Textformat und Metadaten der Data-Layer-Embeddings (Retrieval-Vertrag) |
| `PiiDetection` | regelbasierte PII-Erkennung als Stage, Propagation auf Attribute |
| `SearchService` | globale Teilstring-Suche über Katalog und Ontologie mit Filtern und Ranking |
| `RuleService` | Regeln (gespeicherte Suche + Tags) anwenden und erneut anwenden |

## Beans

| Bean | Typ |
|---|---|
| `aoConnectionService` | `ConnectionService` |
| `aoCatalogWriter` | `CatalogWriter` (mit `TransactionTemplate`) |
| `aoDataLayerEmbedder` | `DataLayerEmbedder` |
| `aoSearchService` | `SearchService` |
| `aoRuleService` | `RuleService` |
| `aoPiiDetection` | `PiiDetection` |
| `aoIngestService` | `IngestService` + Stages: `PiiDetection`, Regel-Replay (nur wenn die Kompilierung aus ist) |
| `aoIngestScheduler` | `IngestScheduler` |

Alle `@ConditionalOnMissingBean`. Die Auto-Konfiguration läuft nach `VectorAutoConfiguration` und
`LlmAutoConfiguration`.

## Abläufe

### Verbindungen

`ConnectionService` kennt drei Quellen:

| Quelle | Methode | Herkunft |
|---|---|---|
| konfiguriert | `configuredConnections()` | `CONNECTION_STRINGS` (komma-/zeilengetrennt) plus `ao.ingest.connections[]` (`url` oder `datasource-bean`) |
| UI | `uiConnections()` | `catalog_database.connection` (JSON) |

Präzedenz (FR-CON-04, absichtlich asymmetrisch):

- `answeringConnections()` – UI-Verbindungen, wenn es welche gibt, sonst konfigurierte.
- `ingestConnections()` – konfigurierte, wenn es welche gibt, sonst UI-Verbindungen.

`connector(spec)` öffnet einen Konnektor lazy und cacht ihn unter dem Datenbanknamen;
`refresh()` schließt alle gecachten Konnektoren (nach Anlegen/Löschen). `openConnector` wählt
`Connectors.wrap` für `datasource_bean`, sonst `Connectors.open`.

Das UI-JSON wird von `fromJson` gelesen und akzeptiert zwei Formen:

```json
{ "url": "jdbc:postgresql://db:5432/sales", "username": "ro", "password": "…",
  "schemas": ["public"], "table_allow_regex": "…", "table_deny_regex": "…" }

{ "type": "oracle|sqlserver|h2|postgres", "host": "db", "port": "1521", "database": "ORCLPDB1",
  "username": "ro", "password": "…" }
```

`publicConnection` / `publicConnections` entfernen `password`, `pwd`, `secret`, `token`,
`api_key`, `private_key`, bevor etwas die API verlässt.

### Ein Ingest-Lauf (`IngestService.doRun`)

1. Konnektor holen.
2. `CatalogExtractor.extract(db, TableFilter.of(spec))` liefert einen `Catalog` aus Records
   (`Table` mit `Column`s, `ForeignKey`s, Views, Kommentaren, Query-Historie). Hat eine gelistete
   Relation keine Spalten, wirft er `IncompleteCatalogExtractionException` – der Lauf dieser
   Datenbank bricht ab, der Bestand bleibt.
3. `CatalogWriter.write` in **einer Transaktion**:
   - Datenbank anlegen/aktualisieren (bei UI-Verbindungen bleibt das gespeicherte JSON erhalten),
   - Schemas, Tabellen, Spalten per Upsert unter **stabilen IDs** (Schlüssel `schema.table`
     bzw. `schema.table.column`),
   - nicht mehr vorhandene Tabellen, Spalten und Schemas löschen (Kaskade räumt abhängige Zeilen).
     **Vorher** werden die ColumnAttributes dieser Tabellen und Spalten gelöscht
     (`SemanticRepository.attributeIdsOf` / `deleteAttributes`): `column_attribute.table_id` hat
     keinen Fremdschlüssel, die Attribute blieben sonst als Waisen zurück,
   - deklarierte FKs upserten und nicht gesehene über `last_seen` entfernen,
   - `pulled` setzen.
4. Embeddings entfernter Tabellen und Spalten (`data_objects_layer`) und der mit ihnen gelöschten
   ColumnAttributes (`semantic_layer`) löschen. `WriteResult` trägt dafür `removedTableIds`,
   `removedColumnIds` und `removedAttributeIds`.
5. Query-Historie schreiben: je Statement Hash des normalisierten Texts, Zähler, letzte Ausführung,
   Verknüpfung zu Tabellen und Spalten, beobachtete Joins und Unions als `column__join` /
   `column__union` mit Referenz `"<sql id>|<condition>"`. Ein Statement, das der Parser nicht
   auswerten kann, wird übersprungen (DEBUG-Log) – es bricht den Ingest der Datenbank nicht ab.
6. `Stage.afterCatalogWrite` – PII-Erkennung.
7. `DataLayerEmbedder.embedDatabase`: Zeilen bauen, in Chunks zu 128 einbetten. Der alte Bestand
   der Datenbank wird **unmittelbar vor dem ersten Upsert** gelöscht; scheitert schon das erste
   Embedding, bleibt er unverändert.
8. `Stage.afterIngest` – Regel-Replay (bei abgeschalteter Kompilierung), Dataset-Sync (NL-Modul).
9. INFO-Log mit Tabellen, Spalten, Diff, Queries, Embeddings und Dauer.

Fehler in Stages werden als WARN geloggt und brechen den Ingest nicht ab (NFR-REL-01).
`runAll()` ingestet alle Verbindungen nacheinander; der Fehler einer Datenbank stoppt die anderen
nicht.

`deleteDatabase(name)` löscht **zuerst die semantische Schicht** der Datenbank
(`SemanticRepository.deleteSemanticLayer`: Terms, ColumnAttributes, SqlAttributes, Custom Analyses –
geteilte Terms bleiben, siehe [`ao-store`](ao-store.md)), dann den Katalog (Kaskade) und beide
Vektor-Layer, und frischt die Konnektoren auf. Die Reihenfolge ist zwingend: Terms und Attribute
sind nur über die Katalogtabellen auffindbar. Die Methode wartet auf einen laufenden Ingest (dasselbe
Lock wie `run`).

### Scheduler

- `start()` (Phase `Integer.MAX_VALUE - 100`, also nach dem Webserver) triggert den ersten Lauf,
  wenn `ao.ingest.scheduler-enabled`.
- Nach jedem Lauf wird der nächste in `ao.ingest.interval-hours` geplant – gemessen ab **Ende** des
  Laufs.
- `trigger()` storniert den geplanten Lauf und startet sofort (nicht blockierend). Kommt der
  Trigger, **während ein Lauf läuft**, wird er nur vermerkt; der nächste Lauf startet dann direkt
  im Anschluss statt nach dem Intervall. Es gibt immer genau eine geplante Aufgabe – ein zweiter
  `schedule`-Aufruf würde eine zweite, sich endlos selbst verlängernde Timer-Kette erzeugen.
- `onRunCompleted(hook)` – der Kompilierungs-Scheduler wartet so auf den ersten Ingest.
- Ein Single-Thread-Executor auf Virtual Threads; Läufe überlappen nie.

### Embedding-Texte (Retrieval-Vertrag)

```
Tabelle: database_name: X, schema_name: Y, table_name: Z, table_description: …, columns: {name: a, data_type: int},{name: b, data_type: text, description: …}
Spalte:  database_name: X, schema_name: Y, table_name: Z, column_name: a, data_type: int, column_description: …, sample_values: 1, 2, 3
```

Metadaten je Punkt: `id`, `label` (`Table`/`Column`), `name`, `source_path` (`auto_ontology:<id>`),
`schema_name`, `database_name`. Diese Formate entsprechen exakt `build_table_text` /
`build_column_text` des Originals. **Wer sie ändert, ändert die Retrieval-Qualität** und muss alle
Datenbanken neu einbetten.

### PII-Erkennung

`PiiDetection.evaluate(table, column)` normalisiert Namen (camelCase/snake_case/UPPER → Wörter)
und prüft Regellisten aus `pii_detection/rules.py`:

- `DIRECT` – eindeutige PII-Namen (E-Mail, Telefon, IBAN …),
- `CONDITIONAL` – nur in Personentabellen (`PERSON_TABLE`: customers, people, users …), nicht in
  `NON_PERSON_TABLE`,
- `NON_PII` – bekannte Fehlalarme (product name, account type …).

Ergebnis: Kategorie, `not_pii` oder `null` (unentschieden). Treffer bekommen den Tag `PII` (mit
dem festen Benutzer als `tagged_by`); alle bewerteten Spalten `pii_processed = true`. Verarbeitete
Spalten werden nie erneut bewertet – ein manuell entfernter Tag bleibt entfernt. `propagate()`
(nach der Kompilierung) überträgt PII auf ColumnAttributes und SqlAttributes. Agent und
Kompilierung fragen mit `piiColumnIds()` **einmal** alle PII-Spalten ab, um Beispielwerte nicht an
externe LLMs zu geben; `isPii(columnId)` (zwei Store-Abfragen je Aufruf) ist nur für Einzelprüfungen
gedacht.

### Suche und Regeln

- `SearchService.search(request)`: Teilstring-Suche (`contains`, mindestens zwei Zeichen) über
  `Database`, `Schema`, `Table`, `View`, `Column`, `CustomAnalysis`, Terms und Attribute; Filter nach
  Typ, Tag (inkl. `(blanks)` für „ohne Tag“) und Datenbereich; optional in Beschreibungen. Ranking:
  Name enthält alle Tokens → Synonym als ganzes Wort → kürzerer Name. Höchstens 200 Treffer
  (`CAP`); `count` ist ungedeckelt. Synonyme liegen als JSON-Text in der Spalte; der SQL-Filter
  trifft deshalb auch Klammern und Anführungszeichen. Ein Term zählt nur als Treffer, wenn der
  Suchtext im Namen, (bei eingeschalteter Option) in der Beschreibung oder in einem **einzelnen
  Synonym** vorkommt.
- `RuleService.apply(rule)` führt die gespeicherte Suche aus und setzt die Tags der Regel auf genau
  diese Treffer (mit `rule_id`). `replayAll()` nach Ingest bzw. Kompilierung; Fehler werden nur
  geloggt.

## Konfiguration

| Property | Default | Wirkung |
|---|---|---|
| `ao.ingest.connection-strings` (`CONNECTION_STRINGS`) | leer | konfigurierte Verbindungen |
| `ao.ingest.connections[]` | – | konfigurierte Verbindungen mit Scoping oder DataSource-Bean |
| `ao.ingest.interval-hours` (`INGEST_INTERVAL_HOURS`) | 24 | Intervall des Schedulers |
| `ao.ingest.scheduler-enabled` | `true` | Scheduler an/aus (API-Instanz im Worker-Betrieb: `false`) |
| Laufzeitschalter `pii_detection_enabled` | `false` | PII-Stage aktiv |
| Laufzeitschalter `semantic_compilation_enabled` | `false` | ob nach dem Ingest Regeln erneut angewandt werden |

## Erweiterungspunkte

- **Schritt nach dem Ingest:** `IngestService.addStage(new IngestService.Stage() { … })` mit
  `afterCatalogWrite` (vor dem Embedding) und/oder `afterIngest`. So hängt sich das NL-Modul an.
- **Bean ersetzen:** z. B. ein eigener `ConnectionService` für einen Credential-Store (Vault).
- **Neue Suchtypen:** in `SearchService.TYPES` und `tagTarget` ergänzen.

## Code-Beispiele

### Einen Schritt in den Ingest einhängen

```java
@AutoConfiguration(after = IngestionAutoConfiguration.class)
public class FreshnessAutoConfiguration {
    @Bean
    public FreshnessCheck aoFreshnessCheck(IngestService ingest, CatalogRepository catalog) {
        FreshnessCheck check = new FreshnessCheck(catalog);
        ingest.addStage(new IngestService.Stage() {
            @Override
            public void afterCatalogWrite(String databaseId, String databaseName) {
                // Katalog steht im Store, die Embeddings folgen erst: hier ändern, was eingebettet werden soll
                check.markStaleTables(databaseId);
            }

            @Override
            public void afterIngest(String databaseId, String databaseName) {
                check.report(databaseName);            // alles fertig, auch die Embeddings
            }
        });
        return check;
    }
}
```

Eine Exception in einer Stage wird als WARN geloggt und bricht den Ingest nicht ab. Beide Methoden
sind `default` – man überschreibt nur, was man braucht.

### Ingest von Hand auslösen

```java
ingestScheduler.trigger();                          // alle Verbindungen, nicht blockierend, setzt den Timer zurück

ConnectionSpec spec = connections.ingestConnections().get(0);
IngestService.Result r = ingest.run(spec);          // eine Verbindung, blockierend (wartet auf das Lock)
log.info("{}: {} Tabellen, +{} -{}, {} Statements, {} Embeddings",
        r.databaseName(), r.tables(), r.added(), r.removed(), r.queries(), r.embedded());
```

```bash
curl -X POST localhost:8080/internal/ingest          # dasselbe von außen (Worker-Endpunkt)
```

### Verbindungen konfigurieren

```yaml
ao:
  ingest:
    connections:
      - name: erp                                    # der Datenbankname in Katalog und Vektorstore
        url: jdbc:oracle:thin:@//ora:1521/ERP
        schemas: [SALES, MASTERDATA]                 # Allowlist, case-insensitiv
        table-allow-regex: ^(ORD|CUST|INV)           # find() auf dem unqualifizierten Namen
        table-deny-regex: (_BAK|_TMP)$               # Deny gewinnt
      - name: analytics
        datasource-bean: analyticsDataSource         # vorhandene DataSource des Hosts, kein eigener Pool
```

```java
TableFilter filter = new TableFilter(List.of("SALES"), "^(ORD|CUST)", "_BAK$");
filter.allows("sales", "ORDERS");        // true  – Schema case-insensitiv
filter.allows("SALES", "ORDERS_BAK");    // false – Deny gewinnt
filter.allows("HR", "ORDERS");           // false – Schema nicht in der Allowlist
filter.allows("SALES", "orders");        // false – Regex case-sensitiv
```

### Beobachtetes SQL auswerten

```java
QueryHistoryParser.Parsed p = QueryHistoryParser.parse(
        "select c.name, count(*) from s.orders o join s.customers c on o.customer_id = c.id group by c.name;");
p.sql();       // normalisiert: Leerraum zusammengezogen, ohne Semikolon – Basis des Hashes
p.tables();    // [s.orders, s.customers]
p.joins();     // [ColumnPair[left=s.orders.customer_id, right=s.customers.id, condition="o.customer_id = c.id"]]
p.columns();   // alle aufgelösten Spaltenreferenzen

QueryHistoryParser.parse("UPDATE t SET a = 1");                                     // null: kein lesendes Statement
QueryHistoryParser.parse("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d");  // null: schreibendes CTE
```

### PII-Regeln und Suche

```java
PiiDetection.evaluate("CUSTOMERS", "EMAIL");          // "email_address"  (DIRECT)
PiiDetection.evaluate("people", "full_name");         // "person_name"    (CONDITIONAL, Personentabelle)
PiiDetection.evaluate("products", "name");            // "not_pii"
PiiDetection.evaluate("customers", "customerName");   // null             (unentschieden → kein Tag)

Set<String> pii = piiDetection.piiColumnIds();        // einmal holen, dann je Spalte nur noch contains()

List<Map<String, Object>> hits = search.search(SearchService.request(Map.of(
        "search_term", "customer",
        "filters", Map.of("objects", List.of("Table", "Term"), "description", true))));
// je Treffer: id, type, name, description, certified, parent_id, synonyms, breadcrumbs
```

## Tests

`IngestServiceTest` (H2, In-Memory-Vektorstore, Hash-Embedding):

| Test | Prüft |
|---|---|
| `ingestWritesCatalogHistoryAndEmbeddings` | Katalog, Query-Historie und Data-Layer-Embeddings nach einem Lauf |
| `textsFollowTheRetrievalContract` | exaktes Textformat von `EmbeddingTexts` |
| `piiRules` | Entscheidungen von `PiiDetection.evaluate` |
| `semanticLayerFollowsTheCatalog` | entfernte Spalte nimmt ihr ColumnAttribute samt Embedding mit; `deleteDatabase` löscht auch Terms und Attribute |
| `triggerDuringARunDoesNotDuplicateTheSchedule` | ein Trigger während eines Laufs führt zu genau einem Folgelauf |
| `parserFindsJoinsAndRejectsDml` | `QueryHistoryParser`: Joins erkannt, DML/DDL und schreibende CTEs ignoriert, lesende CTEs und Unterabfragen ausgewertet |
| `incompleteExtractionFails` | Vollständigkeitsprüfung des Extraktors |

Nicht eigens getestet sind die Ersetzungslogik bei fehlschlagendem Embedding und
`SearchService`/`RuleService`; sie laufen nur indirekt im E2E-Test von `ao-app` mit.

## Fallstricke

- **Umbenennen ist Löschen + Anlegen.** Eine umbenannte Tabelle bekommt eine neue ID. Ihre
  ColumnAttributes, Semantic FKs, Tags und die Zuordnungen zu Terms verschwinden; die Terms selbst
  bleiben (ohne Tabelle) bestehen. Die neue Tabelle hat keinen Term und wird beim nächsten
  Kompilierungslauf neu bearbeitet.
- **Credentials im Klartext:** UI-Verbindungen speichern das Passwort im JSON von
  `catalog_database.connection`. Die API gibt es nie zurück, aber Store-Backups enthalten es.
- Das aus `host`/`port` gebaute SQL-Server-URL setzt `trustServerCertificate=true`.
- Präzedenz: Gibt es konfigurierte **und** UI-Verbindungen, ingestet der Scheduler nur die
  konfigurierten, während Chat und Kompilierung die UI-Verbindungen nutzen.
- Die PostgreSQL-Historie aus `pg_stat_activity` sieht nur den jeweils letzten Befehl je
  Verbindung; viele Statements erscheinen nie.
- Ein Store-Commit mit anschließendem Vektorfehler hinterlässt Katalogzeilen ohne Embeddings; der
  nächste Lauf oder `/internal/vector/reconcile` repariert das.

## Anforderungsbezug

FR-ING-01 bis FR-ING-04, FR-CON-03/04/06, FR-GOV-05 (Suche), FR-GOV-07 (Regeln), FR-GOV-09 (PII),
FR-VEC-06 (Ersetzungslogik), NFR-REL-01, NFR-SEC-02/06.
