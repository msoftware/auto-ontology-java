# Modul `ao-semantic`

Paket `de.jentsch.autoontology.semantic` · [Zurück zur Übersicht](../README.md)

## Zweck

Die **semantische Kompilierung**: aus dem eingelesenen Katalog eine Ontologie bauen – Terms
(Geschäftsbegriffe) mit ColumnAttributes, Semantic FKs (Joins), SqlAttributes aus der
Query-Historie und Bridge-Table-Joins – und sie in `semantic_layer` einbetten. Dazu der Scheduler
mit Status, Abbruch und Reset.

Vorbild im Original: `semantic/compile.py`, `pipeline.py`, `visit_enter.py`, `term_extractor.py`,
`fk_suggester.py`, `deterministic.py`, `semantic_fk.py`, `sql_attribute_suggester.py`,
`bridge_tables.py`, `embed.py`, `date_format.py`, `domain.py`,
`ingestion_service/semantic_scheduler.py`.

## Abhängigkeiten

`api`: `ao-ingestion` (und damit Store, Vector, LLM, Connectors). Ressourcen: 11 Prompts unter
`src/main/resources/prompts/`.

## Klassen

| Klasse | Aufgabe |
|---|---|
| `SemanticAutoConfiguration` | Beans `aoSemanticEmbedder`, `aoSemanticCompiler`, `aoSemanticCompilationService` |
| `SemanticCompilationService` | Scheduler (`SmartLifecycle`), Lauf über alle Datenbanken, Abbruch, Reset, Status, Historie, Nachlauf-Hooks |
| `SemanticCompiler` | die vier Stufen für **eine** Datenbank |
| `Profiler` | Spaltenprofil: Stichprobe, Eindeutigkeit, Top-Werte, Distinct-Probe, Datumsformat, Sampling-Breaker |
| `TableTaxonomy` | LLM-Schritte je Tabelle: FK-Vorschläge und Junction-Urteil, Attribut-Specs mit Beschreibungen, Term-Extraktion, Synonyme |
| `SemanticEmbedder` | Texte und Schreiben der Semantic-Layer-Embeddings (Term, ColumnAttribute, SqlAttribute, CustomAnalysis) |
| `DateFormats` | Port von `date_format.py`: Notation nur, wenn alle Stichproben sie parsen |
| `DomainContext` | optionaler Domain-Kontext aus `.semantic_summaries/<db>.json` |
| `SemanticModels` | LLM-Ausgabe-Records der Kompilierung |

## Beans

| Bean | Typ | Abhängigkeiten (Auszug) |
|---|---|---|
| `aoSemanticEmbedder` | `SemanticEmbedder` | `Embedder`, `VectorStore`, `SemanticRepository` |
| `aoSemanticCompiler` | `SemanticCompiler` | Repositories, `LlmGateway`, `Embedder`, `VectorStore`, `RerankClient`, `RuntimeSettings`, `PiiDetection` |
| `aoSemanticCompilationService` | `SemanticCompilationService` | Compiler, `ConnectionService`, `HistoryRepository`, `RuleService`, `PiiDetection`, `IngestScheduler` |

## Abläufe

### Scheduler (`SemanticCompilationService`)

```
start()  (Phase MAX-200)
  └─ wenn ao.semantic.scheduler-enabled: beim IngestScheduler anmelden → erster Lauf nach dem ersten Ingest
trigger()                      → sofortiger Lauf (nicht blockierend), danach alle ao.semantic.interval-hours
                                 läuft gerade ein geplanter Durchgang: nur vermerken, Folgelauf direkt danach
runNow()
  ├─ semantic_compilation_enabled aus?  → nichts tun (false)
  ├─ läuft schon?                        → nichts tun (false)
  ├─ history.start                       (im try: ein Store-Fehler lässt "running" nicht hängen)
  ├─ je answeringConnection mit Katalogeintrag: SemanticCompiler.compile(dbId, name, connector, token)
  ├─ history.finish(succeeded | failed, Zusammenfassungen)
  └─ Nachlauf (Fehler nur geloggt): RuleService.replayAll, PiiDetection.propagate, onRunCompleted-Hooks
abort()  → token.cancel()          (kooperativ: laufende Tabellen werden fertig)
reset(db) → Datenbank auflösen (unbekannter Name: nichts tun, false) → abort, bis 120 s warten,
            deleteSemanticLayer, Semantic-Layer-Embeddings löschen, trigger → true
status() → calculated (≥ 1 Term), running, last_success_at, last_failure_at (nur wenn jünger)
```

**Reset und Datenbankname:** `reset(null)` setzt **alle** Datenbanken zurück, `reset("sales")` nur
diese. Ein Name, der nicht im Katalog steht, setzt **nichts** zurück – der Name wird aufgelöst, bevor
irgendetwas gelöscht wird. (Früher fiel ein Tippfehler im Namen auf „alles löschen“ durch.) Die
öffentliche Route antwortet in diesem Fall mit 404.

**Genau eine Timer-Kette:** Wie beim Ingest-Scheduler plant `trigger()` nichts Zweites ein, während
ein Durchgang läuft; sonst liefen danach zwei Ketten, die sich beide alle 24 h selbst verlängern.

Ausgelöst wird ein Lauf

- durch den Scheduler,
- über `PUT /api/configurations/semantic-compilation` mit `{"enabled": true}`,
- über `POST /api/semantic-compilation/trigger` bzw. `/internal/semantic/compile`,
- nach `POST /api/semantic-compilation/reset`.

### `SemanticCompiler.compile`

Liefert eine `Summary(database, tables, terms, semanticFks, sqlAttributes, bridges, millis)`, die
als eine INFO-Zeile geloggt wird.

#### Stufe 1 – Taxonomie (`stageTaxonomy`)

Nur Tabellen **ohne** Term (`tablesWithoutTerm`). Eine erneute Kompilierung lässt vorhandene Terms
also unangetastet; für einen Neuaufbau braucht es den Reset.

```
für jede Tabelle parallel (Virtual Threads, höchstens ao.semantic.llm-max-inflight gleichzeitig):
  ├─ cancel? → überspringen
  └─ processTable
      ├─ Profiler.profile → sample_values, is_unique, format in catalog_column schreiben
      ├─ TableTaxonomy.suggestForeignKeys  → zusätzliche FK-Spalten + Junction-Urteil (LLM)
      │     junction nur, wenn LLM ja sagt UND ≥ 2 FK-Spalten existieren
      ├─ TableTaxonomy.attributeSpecs      → je Nicht-FK-Spalte ein Spec; Spalten ohne Kommentar
      │     bekommen eine LLM-Beschreibung (Batches zu 15)
      └─ TableTaxonomy.extractTerms        → Terms mit Spaltenzuordnung und Anzeige-Labels, Synonyme
in kanonischer Tabellenreihenfolge (Reihenfolge der Futures):
  └─ commit: markJunction, mergeTerm, linkTableTerm, mergeColumnAttribute, Embedding des Terms
```

Die Commit-Reihenfolge ist **bewusst deterministisch** (`_OrderedCommitQueue` im Original): Wenn zwei
Tabellen denselben Term-Namen vorschlagen, gewinnt immer dieselbe. Schlägt eine Tabelle fehl, wird
sie geloggt und übersprungen.

**Beispielwerte an das LLM (NFR-SEC-04):** Enthält eine Tabelle eine PII-Spalte und ist
`ao.security.pii-samples-to-llm=false`, gehen für **die ganze Tabelle** keine Beispielwerte in die
Prompts. Das gilt für alle drei LLM-Schritte der Tabelle – FK-Vorschläge (`columnLine`),
Spaltenbeschreibungen und die an vorhandene Kommentare angehängten Beispiele –, außerdem für die
Semantic-FK-Auswahl in Stufe 2 (dort je Spalte: `Sample values: (withheld)`). Die PII-Spalten werden
einmal je Durchgang mit `PiiDetection.piiColumnIds()` geholt.

**LLM-Antworten werden defensiv gelesen:** Fehlende Listen (`terms`, `fk_suggestions`,
`pk_column_names`, `descriptions`, `synonyms`, `suggestions`) gelten als leer, Einträge ohne Namen
bzw. ohne Ausdruck werden übersprungen. Eine unvollständige Antwort führt damit zum Fallback (Term
aus dem Tabellennamen) statt zu einer `NullPointerException`, die die Tabelle bei jedem Durchgang
erneut scheitern ließe.

#### Profiler

| Konstante | Wert | Bedeutung |
|---|---|---|
| `SAMPLE_LIMIT` | 1000 | Zeilen der Stichprobe über `sampleQuery` |
| `TIMEOUT_SECONDS` | 120 | Timeout der Stichprobe |
| `TOP_N` | 5 | häufigste Werte je Spalte |
| `LOW_CARDINALITY_MAX` | 25 | bis zu so vielen Werten: zusätzliche `SELECT DISTINCT`-Probe bei Textspalten (abschaltbar über `distinct_value_probing_enabled`) |
| `MAX_SAMPLE_LEN` | 30 | längere Strings werden nicht als Beispiel gespeichert |
| `EXCLUDED_TYPES` | date, time, timestamp, datetime, uuid | keine Beispielwerte |
| `MAX_CONSECUTIVE_FAILURES` | 3 | danach schaltet der Sampling-Breaker Proben für die Datenbank ab |

`DateFormats.infer(values)` meldet ein Format (z. B. `YYYYMMDD`, `YYYY-MM-DD HH:MM:SS`) nur, wenn
alle Werte (bis 200) es parsen; bei nicht unterscheidbaren Tag/Monat-Reihenfolgen gibt es kein
Format.

#### Stufe 2 – Semantic FKs (`stageSemanticFks`)

Für jede Spalte ohne Semantic FK und ohne HAS_ATTRIBUTE (also typischerweise FK-Spalten):

1. **Fast Path:** deklarierter FK → Kante zum ColumnAttribute der Zielspalte; Selbstreferenzen auf
   dieselbe Tabelle werden verworfen.
2. **Fallback:** Spaltentext einbetten, in `semantic_layer` 15 Treffer holen und auf die besten 5
   ColumnAttributes anderer Tabellen filtern; das LLM wählt (`FkHitSelection`). Enthält es sich,
   wird live geprüft, welcher Kandidat überlappende Werte hat (`overlaps`, read-only, begrenzt).
3. `pruneDisjoint`: Kanten, deren Beispielwerte im Ziel nie vorkommen, werden gelöscht. Gelöscht
   wird nur, wenn die Probe **lief und nichts fand**. `overlaps` liefert `true`, `false` oder `null`;
   `null` heißt „Probe gescheitert“ (Timeout, fehlendes Recht, nicht castbarer Typ) und lässt die
   Kante stehen – „unbekannt“ ist nicht „disjunkt“.
4. Join-Defaults: je (Quelltabelle, Attribut) genau eine Kante `use_in_join_paths = true`; bei
   mehreren Kandidaten entscheidet das LLM (`SemanticFkJoinDefaultSelection`).

#### Stufe 3 – SQL Attributes (`stageSqlAttributes`)

Je Term die beobachteten Statements seiner Tabellen laden, daraus Filter-, Aggregat- und
Funktionsausdrücke extrahieren (unparsbare Statements tragen nichts bei), die Top 10 an das LLM
geben (`Suggestions`), neue Attribute mit `source = 'sql'` speichern und einbetten. Vorhandene
Namen und Vorschläge ohne Namen oder Ausdruck werden übersprungen.

Ranking: Mit `ao.semantic.rank-sql-by-usage=true` (Default) nach `sql_query.total_counter`. Mit
`false` haben alle Ausdrücke denselben Rang wie im Original (`_usage_score` gibt dort immer 0
zurück, ein dokumentierter Bug) und es gilt die Einfügereihenfolge.

#### Stufe 4 – Bridge Tables (`stageBridges`)

Reine FK-Tabellen finden: mindestens zwei **deklarierte** FKs und jede Spalte ist eine FK-Spalte.
Junctions mit Nutzdaten behalten ihren eigenen Term und sind keine Bridges; LLM-vorgeschlagene FKs
zählen hier nicht. Danach per LLM eine strukturelle Join-SQL (bei Selbstreferenz mit Doppel-Alias)
erzeugen (`BridgeSqlAttributeProposal`) und als SqlAttribute mit `source = 'bridgeTable'` am besser
passenden Ziel-Term speichern. Den Ziel-Term wählt das Rerank-Modell, wenn es konfiguriert ist,
sonst der erste Kandidat. Für die Bridge-Tabelle selbst entsteht kein Term.

### Embedding-Texte (Retrieval-Vertrag)

```
Term:            "Term: <name>. <description>"
ColumnAttribute: "ColumnAttribute: <attr> of Term <term> (<synonyme>). <description> Sample values: …"
```

`SemanticEmbedder.termRows` erzeugt Term- und Attribut-Zeilen, `sqlAttributeRow` und
`customAnalysisRow` die übrigen. `refreshTerm(termId)` bettet einen Term nach einer Änderung über
die API neu ein – je Datenbank einmal, auch wenn der Term dort mehrere Tabellen hat. Beispielwerte
werden auf 30 Zeichen (JSON-Werte 60) begrenzt.

### Domain-Kontext

`DomainContext.block(db)` liest optional `.semantic_summaries/<db>.json` (relativ zum
Arbeitsverzeichnis) mit `domains`, `core_entities`, `business_rules` und gibt bis zu 8 Domains und
12 Kernentitäten an die Term-Extraktion. Fehlt die Datei, passiert nichts.

## LLM-Ausgabeschemata (`SemanticModels`)

| Record | Verwendet in |
|---|---|
| `RawTableTermsResult`, `RawTermProposal`, `TermColumnRef` | Term-Extraktion |
| `SynonymExtraction` | Synonyme |
| `FkAndPkResult`, `PotentialFkSuggestion` | FK-Vorschläge, Junction |
| `ColumnDescriptionResult`, `ColumnDescription` | Spaltenbeschreibungen |
| `FkHitSelection` | Semantic-FK-Auswahl |
| `SemanticFkJoinDefaultSelection` | Join-Default |
| `Suggestions`, `SuggestedAttr` | SQL Attributes |
| `BridgeSqlAttributeProposal` | Bridge Tables |

Feldnamen und Beschreibungen stammen aus den Pydantic-Modellen; die Prompts verweisen darauf.

## Konfiguration

| Property / Schalter | Default | Wirkung |
|---|---|---|
| Laufzeitschalter `semantic_compilation_enabled` | `false` | ohne ihn läuft keine Kompilierung |
| Laufzeitschalter `distinct_value_probing_enabled` | `true` | Distinct-Probe im Profiling |
| `ao.semantic.llm-max-inflight` (`SEMANTIC_LLM_MAX_INFLIGHT`) | 6 | parallele Tabellen |
| `ao.semantic.interval-hours` | 24 | Intervall |
| `ao.semantic.scheduler-enabled` | `true` | Scheduler an/aus |
| `ao.semantic.rank-sql-by-usage` | `true` | Ranking der SQL-Ausdrücke |
| `ao.security.pii-samples-to-llm` | `false` | Beispielwerte von PII-Tabellen an das LLM |

## Erweiterungspunkte

- **Nachlauf-Hook:** `SemanticCompilationService.onRunCompleted(runnable)` – das NL-Modul nutzt ihn
  für den Dataset-Sync.
- **Eigener Compiler:** `SemanticCompiler`-Bean ersetzen, z. B. um Stufen abzuschalten.
- **Domain-Wissen:** `.semantic_summaries/<db>.json` bereitstellen.
- **Prompts:** in `src/main/resources/prompts/` (wörtlich aus dem Original – erst nach messbarer
  Verbesserung ändern).

## Code-Beispiele

### Kompilierung steuern

```java
settings.set(RuntimeSettings.SEMANTIC_COMPILATION, true);   // ohne den Schalter passiert nichts
compilation.trigger();                                      // nicht blockierend
boolean ok = compilation.runNow();                          // blockierend; false = übersprungen oder gescheitert
compilation.abort();                                        // kooperativ: laufende Tabellen werden noch fertig

compilation.reset("sales");                                 // nur diese Datenbank; false, wenn unbekannt
compilation.reset(null);                                    // ALLE Datenbanken

Map<String, Object> s = compilation.status();
// {calculated=true, running=false, last_success_at=2026-06-30T00:00:12Z, last_failure_at=null}
```

```bash
curl -X PUT  localhost:8080/api/configurations/semantic-compilation -H 'Content-Type: application/json' -d '{"enabled":true}'
curl -X POST 'localhost:8080/api/semantic-compilation/reset?database_name=sales'     # 202, läuft asynchron
curl -s      localhost:8080/api/semantic-compilation/status
```

### Nach jedem Durchgang etwas tun

```java
compilation.onRunCompleted(() -> {
    // läuft nach Regel-Replay und PII-Propagation, auch nach einem gescheiterten Durchgang
    datasetSync.sync();
});
```

### Die LLM-Schritte ohne Spring testen

```java
ScriptedLlmGateway llm = new ScriptedLlmGateway()
        .on(RawTableTermsResult.class, prompt -> new RawTableTermsResult(List.of(
                new RawTermProposal("Customer", "A buyer of goods", List.of(
                        new TermColumnRef("CUSTOMER_NAME", "Customer Name"))))))
        .on(SynonymExtraction.class, prompt -> new SynonymExtraction(List.of("Client")));
TableTaxonomy taxonomy = new TableTaxonomy(llm);

List<TableTaxonomy.Spec> specs = List.of(
        new TableTaxonomy.Spec("CUSTOMER_NAME", "customerName", "VARCHAR", "Name of the customer", "col-1"),
        new TableTaxonomy.Spec("CITY", "city", "VARCHAR", null, "col-2"));
List<TableTaxonomy.TermProposal> terms = taxonomy.extractTerms(
        Map.of("name", "CUSTOMERS", "description", "All clients"), specs, null);

// terms.get(0): name "Customer", synonyms [Client],
//   attributes {CUSTOMER_NAME=Customer Name, CITY=city}   ← nicht zugeordnete Spalten fallen an den ersten Term
```

Ohne Skript für einen Typ wirft das Gateway `LlmException`, `tryStructured` liefert leer und der
Fallback greift:

```java
new TableTaxonomy(new ScriptedLlmGateway()).extractTerms(Map.of("name", "ORDER_LINES"), specs, null);
// → ein Term "Order Lines" mit der Beschreibung "Business entity represented by table ORDER_LINES"
```

### Namens- und Formatregeln

```java
TableTaxonomy.toTermName("order_lines");        // "Order Lines"
TableTaxonomy.toTermName("HTTPRequestLog");     // "HTTP Request Log"
TableTaxonomy.columnToAttrName("ORDER_DATE");   // "orderDate"

DateFormats.infer(List.of("20250601", "20250615"));         // "YYYYMMDD"
DateFormats.infer(List.of("01/02/2025", "03/04/2025"));     // null – Tag/Monat nicht unterscheidbar
DateFormats.infer(List.of("13/02/2025", "03/04/2025"));     // "DD/MM/YYYY" – der 13. entscheidet
```

### Eigene Embedding-Zeilen schreiben

```java
Map<String, Object> term = semantic.termById(termId).orElseThrow();
List<Map<String, Object>> attrs = semantic.attributesOfDatabase(databaseId).stream()
        .filter(a -> termId.equals(a.get("term_id"))).toList();
semanticEmbedder.write("sales", semanticEmbedder.termRows("sales", term, attrs, true));
// Texte: "Term: Customer. A buyer of goods"
//        "ColumnAttribute: Customer Name of Term Customer (Client). Name of the customer Sample values: Acme, Globex."

semanticEmbedder.refreshTerm(termId);           // dasselbe für alle Datenbanken des Terms, nach einer Änderung
```

## Tests

`SemanticResetTest` (H2, In-Memory-Vektorstore): Reset einer bekannten Datenbank, Reset aller
Datenbanken, und dass ein unbekannter Datenbankname nichts löscht.

Die Kompilierung selbst ist durch `DemoEndToEndTest` (`ao-app`: Demo-Schema bis mindestens
12 Terms) und `NlChartIntegrationTest` (`ao-dashboard-demo`) abgedeckt. Neue Logik in `Profiler`,
`DateFormats` oder `TableTaxonomy` lässt sich ohne Spring testen (siehe Code-Beispiele).

## Fallstricke

- **Inkrementell nur für neue Tabellen.** Geänderte Spalten einer Tabelle mit Term werden nicht neu
  kompiliert; dafür ist ein Reset (für die Datenbank) nötig.
- Die Kompilierung nutzt `answeringConnections()` (UI-Verbindungen zuerst), der Ingest
  `ingestConnections()`. Siehe [`ao-ingestion`](ao-ingestion.md).
- Zwei Nebenläufigkeitsgrenzen wirken zusammen: parallele Tabellen (`ao.semantic.llm-max-inflight`)
  und parallele LLM-Aufrufe im Gateway (`ao.llm.max-inflight`).
- Ohne LLM liefern die `tryStructured`-Aufrufe leer; es entstehen Fallback-Terms aus den
  Tabellennamen (`TableTaxonomy.toTermName`) und keine LLM-Beschreibungen.
- `reset` wartet höchstens 120 s auf das Ende eines laufenden Durchgangs und löscht danach in jedem
  Fall.
- **Terms sind datenbankübergreifend.** Der Merge-Schlüssel ist (`name`, `source`). Zwei Datenbanken
  mit einer Tabelle `CUSTOMERS` teilen sich den Term „Customer“; der Reset einer der beiden lässt
  ihn deshalb stehen.
- Die Live-Proben (Stichprobe, Distinct, Overlap) laufen gegen die Quell-DB; bei großen Tabellen
  kann die 1000-Zeilen-Stichprobe ohne Index teuer sein.

## Anforderungsbezug

FR-SEM-01 bis FR-SEM-09, NFR-PERF-02, NFR-REL-01, NFR-SEC-04.
