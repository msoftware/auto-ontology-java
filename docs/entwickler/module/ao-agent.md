# Modul `ao-agent`

Paket `de.jentsch.autoontology.agent` · [Zurück zur Übersicht](../README.md)

## Zweck

Der **Text-to-SQL-Agent**: eine schlanke Graph-Engine, die Knoten des Originals
(`retrieval/text_to_sql/agents/*.py`) mit identischen Namen und Routing ausführt, plus
Beantwortbarkeitsprüfung (Entity Coverage), Join-Pfad-Suche, statische SQL-Prüfung und
Chart-Empfehlung für Chat-Ergebnisse.

Das ist das Herz des Systems. Wer die Antwortqualität verbessern will, arbeitet fast immer hier.

## Abhängigkeiten

`api`: `ao-semantic` (und damit alles darunter). Ressourcen: 34 Prompt-Dateien und
`calculation_subtypes.json` unter `src/main/resources/prompts/`.

## Klassen

| Klasse | Aufgabe |
|---|---|
| `AgentAutoConfiguration` | Beans `aoJoinPathFinder`, `aoAgentServices`, `aoAgentRunner`, `aoEntityCoverageService`, `aoChartAdvisor` |
| `AgentGraph` | Graph-Engine: Knoten, Kanten, Router, Recursion-Limit, `step`/`sql`-Events, MDC |
| `AgentRunner` | baut den Graphen, Initialzustand, Follow-up-Auflösung, Gesamtwiederholung, deterministischer Fallback, `result`/`error`-Events |
| `AgentState` | typisierter Zustand; laufbezogene Werte in `pathState` unter den Schlüsselnamen des Originals |
| `AgentServices` | Record mit allen Abhängigkeiten der Knoten |
| `Nodes` | eine Methode je Knoten |
| `Retrieval` | Vektorsuche nach Kandidaten, Deduplizierung, Wahl der Datenbank |
| `JoinPathFinder` | Tabellengraph aus Semantic FKs und FKs, kürzeste Pfade (BFS) |
| `SqlStaticChecks` | statische Prüfung mit JSqlParser: read-only, parsebar, Katalognamen, degenerierte Prädikate, Quoting |
| `EntityCoverageService` | Beantwortbarkeit: Extraktion → Retrieval → Abdeckungsgrad |
| `ChartAdvisor` | LLM-Empfehlung eines Dashboard-Charts für Ergebniszeilen (Modus B) |
| `AgentPrompts` | Bausteine für Prompts, die im Code zusammengesetzt werden (Systemprompt, Dialekt-, Projektions-, Evidenzregeln, Subtypen) |
| `AgentModels` | LLM-Ausgabe-Records des Agenten |
| `NodeLabels` | Knotenname → UI-Label (1:1 aus `node_labels.py`) |
| `AgentEvents` | Fabrik der Stream-Events und `Sink`-Interface |

## Die Graph-Engine

```java
AgentGraph g = new AgentGraph(recursionLimit);
g.node("question_intent", nodes::questionIntent)
 .edge("question_intent", "question_extraction")
 .route("validate_sql_query", st -> st.get("error") == null ? "execute_sql_query" : retryOr(st, max));
g.run("question_intent", state, sink, cancelToken);
```

Ablauf je Schritt in `AgentGraph.run`:

1. `cancel.check()`, Besuchszähler gegen das Recursion-Limit (`RecursionLimitException`).
2. `step`-Event mit `phase: start`, MDC `ao.node` setzen.
3. Knoten ausführen. Eine `RuntimeException` wird zu `NodeException(node, cause)`.
4. INFO-Log mit Dauer.
5. `step`-Event mit `phase: end` und dem **letzten Thought, den genau dieser Knoten in diesem Schritt
   geschrieben hat** (`recordThought`).
6. Hat sich `pathState.validated_sql` geändert, ein `sql`-Event.
7. Router aufrufen; ohne Router → `END`.

Die Engine kennt keine Typen; der Zustand ist das veränderliche `AgentState`-Objekt. Knoten
kommunizieren ausschließlich über `pathState`.

## Der Graph

```
question_intent → question_extraction → retrieve_candidates
retrieve_candidates ──information──▶ information_agent → END
                    └─sonst────────▶ prepare_candidates → construct_sql_from_candidates
construct_sql_from_candidates ── kein sql_code ─▶ unconstructable_sql_response → END
                              └─ sonst ────────▶ validate_sql_query
validate_sql_query   ── ok ─▶ execute_sql_query      ── Fehler ─▶ retryOr
execute_sql_query    ── ok ─▶ check_empty_like_result ── Fehler ─▶ retryOr
check_empty_like_result ── empty_like_flagged ─▶ retryOr   └─ sonst ─▶ check_value_repair
check_value_repair   ── repaired ─▶ validate_sql_query
                     ── regenerate (Versuche übrig) ─▶ reconstruct_sql
                     └─ sonst ─▶ format_and_respond → END
reconstruct_sql      ── kein sql_code ─▶ unconstructable_sql_response   └─ sonst ─▶ validate_sql_query

retryOr(st, max): failed_attempts ≥ max ? unconstructable_sql_response (mit Erklärung) : reconstruct_sql
```

Nicht gebaut werden `refine_evidence`, `validate_sql_values`, `precheck_combined` und der
Kumo-Zweig; der Graph routet wie das Original bei abgeschalteten Proben. Siehe
[`../../ABWEICHUNGEN.md`](../../ABWEICHUNGEN.md).

## Wichtige `pathState`-Schlüssel

| Schlüssel | Gesetzt von | Bedeutung |
|---|---|---|
| `question_type`, `calculation_subtype`, `sql_template` | `question_intent` | Klassifikation, Subtyp-Vorlage aus `calculation_subtypes.json` |
| `normalized_question`, `extracted_evidence`, `target_db` | `question_intent` | umformulierte Frage, Inline-Evidenz, erkannte Ziel-DB |
| `entities`, `subject`, `sanitized_question` | `question_extraction` | Entitäten für die Suche |
| `candidates`, `data_candidates`, `target_database` | `retrieve_candidates` | Treffer aus Semantic und Data Layer, gewählte Datenbank |
| `relevant_tables`, `relevant_columns`, `join_paths`, `custom_analyses`, `database_id`, `dialect` | `prepare_candidates` | Schema-Kontext für die SQL-Synthese |
| `sql_code`, `sql_generation_result`, `sql_construction_messages` | `construct_sql…`, `reconstruct_sql` | aktueller SQL-Entwurf und Konversation mit dem LLM |
| `validated_sql` | `validate_sql_query` | SQL, das alle Prüfungen bestanden hat |
| `error`, `failed_attempts` | Prüf- und Ausführungsknoten | letzter Fehler, Liste aller Fehlversuche (SQL + Fehler) |
| `last_intent_rejected_sql` | `validate_sql_query` | Kandidat für den deterministischen Fallback |
| `sql_columns`, `sql_response_from_db`, `sql_column_types`, `row_count` | `execute_sql_query` | Ergebnis |
| `empty_like_flagged`, `value_repair` | Soft Checks | Routing-Signale |
| `final_response` | `format_and_respond`, `information_agent`, `unconstructable` | Antwort-Map |
| `unconstructable`, `unconstructable_explanation` | `unconstructable_sql_response` | Ende ohne SQL |
| `thoughts_log` | alle | Trace (`node`, `thought`) |

Felder außerhalb von `pathState` (`initialQuestion`, `standaloneQuestion`, `evidence`, `messages`,
`connectors`, `glossary`, `domainRules`, `sqlExamples`, …) beschreibt `AgentState`. `snapshot()`
sichert den Ausgangszustand, `restore()` stellt ihn für die Gesamtwiederholung wieder her.

## Die Knoten im Detail (`Nodes`)

| Knoten | Rolle | Kern der Implementierung |
|---|---|---|
| `questionIntent` | NON_REASONING | `QuestionIntentModel`: Typ, Subtyp, umformulierte Frage, Inline-Evidenz, relevante SQL-Beispiele, Ziel-DB (Ähnlichkeit ≥ 0,8 gegen die Konnektornamen). Ohne LLM-Antwort oder ohne `question_type` in der Antwort: `calculation`/`match_based`. Widerspricht die erkannte Ziel-DB einem übergebenen `target_db`, wird ein Fehler geworfen |
| `questionExtraction` | NON_REASONING | Entitäten (max. `ao.agent.sql-gen-max-entities`) und Subjekt mit Glossar/Akronymen |
| `retrieveCandidates` | NON_REASONING | je Entität Suche in `semantic_layer` (Labels ColumnAttribute, SqlAttribute, CustomAnalysis, Term; k = 6, Distanz ≤ 0,75), Subjekt-Term (k = 3), ganze Frage + Evidenz für SqlAttributes/CustomAnalyses; zusätzlich Tabellen/Spalten aus `data_objects_layer` (ohne Schwelle, für eine noch leere Ontologie). Datenbank = höchste Entitätsabdeckung, dann beste Distanz. Ein LLM-Filter entfernt irrelevante SqlAttributes und CustomAnalyses in einem Aufruf |
| `informationAgent` | REASONING | bis zu 6 Schritte `InformationAgentAction` mit den Tools `list_databases`, `list_tables`, `list_columns`, `list_terms`, `get_term`, `search_semantic_layer`; Beobachtungen auf 4000 Zeichen gekürzt |
| `prepareCandidates` | NON_REASONING | relevante Tabellen (max. 12) laden, LLM-Relevanzfilter, Join-Pfade über `JoinPathFinder.connect`, relevante Queries und Custom Analyses, Samples und Nullability |
| `constructSql` | REASONING | `SQLGenerationModel` (`thought`, `sql_code`, `response`) aus Systemprompt mit Dialekt- und Projektionsregeln, Kandidaten, Schema-Kontext, Join-Pfaden, Beispielen und Subtyp-Vorlage. Kein SQL → `unconstructable` |
| `validateSql` | NON_REASONING | `SqlStaticChecks.check`, danach Intent-Validierung (`IntentValidationModel`) mit den verwendeten Spalten und autoritativen Join-Pfaden |
| `executeSql` | – | Konnektor der Zieldatenbank, Timeout `sql_query_timeout_seconds`, Limit `ao.agent.max-result-rows`. Gibt es für die Zieldatenbank keinen Konnektor, ist das ein Fehler (`No connector available …`) – das SQL läuft nie gegen eine andere Datenbank |
| `checkEmptyLikeResult` | NON_REASONING | nur einmal je Lauf: leeres Ergebnis und `LIKE` im SQL → `EmptyLikeCheckModel` nennt nicht-essenzielle Prädikate |
| `checkValueRepair` | – | leeres Ergebnis: Literale von `=`, `<>`, `IN`, `LIKE` gegen Distinct-Werte der Spalte abgleichen (Groß/Klein, Near-Miss) und ersetzen → `repaired`; sonst einmal `regenerate` |
| `reconstructSql` | NON_REASONING | erster Versuch: `ErrorAnalysis` (`FIXABLE`/`MISSING_DATA`), bei fehlenden Daten weitere Tabellen per Vektorsuche. Prompt mit Fehler, allen früheren Fehlversuchen und der Interpretationshistorie; Fortsetzung der SQL-Konversation |
| `formatAndRespond` | – | Antwort-Map, Entitäts-Links `[[[name/id]]]` (`formatResponse`) |
| `unconstructable` | – | kein LLM-Aufruf: Antwort aus `unconstructable_explanation` (vom SQL-Generator oder von `retryOr`), sonst ein Standardtext; setzt `unconstructable = true` |

**Datenschutz:** `sampleText` gibt keine Beispielwerte von PII-Spalten an das LLM, solange
`ao.security.pii-samples-to-llm=false`. `tablesText` holt die PII-Spalten dafür **einmal** je Prompt
(`PiiDetection.piiColumnIds()`), statt je Spalte den Store zu fragen. `instructionLike` filtert
Werte, die wie Anweisungen aussehen (Prompt-Injection über Daten).

**Robustheit gegen schwache Antworten:** Alle Knoten lesen LLM-Ergebnisse defensiv – fehlende
Listen (`required_entity_name`, `custom_analysis_ids`, `tables_to_remove`, `join_issues`,
`search_queries` …) gelten als leer, ein fehlender Antworttext als leerer Text. Eine unvollständige
Antwort führt in den jeweiligen Fallback, nicht zu einem `error`-Event.

## `AgentRunner`

```java
Map<String, Object> answer = runner.run(
    new AgentRunner.Request(question, evidence, prediction, targetDb, history, connectors),
    event -> …,             // AgentEvents.Sink
    cancellationToken);
```

1. MDC `ao.run` = 8 Zeichen einer UUID.
2. `initialState`: Systemnachricht `AgentPrompts.mainSystemPrompt(today, customPrompts)` plus
   „Answer in the language of the user's question.“; Akronyme als `glossary` und `domainRules`;
   `target_db` (UUID → Name; bei einem Konnektor automatisch); Follow-up-Auflösung mit
   `FollowUpResolutionModel` (letzte 5 Runden, max. 12.000 Zeichen; bei Fehler Originalfrage);
   `snapshot()`.
3. Bis zu **zwei** Durchläufe (`MAX_FULL_PIPELINE_ATTEMPTS`): Endet der erste mit `unconstructable`,
   `restore()` und Neustart ab `question_intent`.
4. Danach, falls immer noch `unconstructable` und `last_intent_rejected_sql` existiert:
   **deterministischer Fallback** – dieses SQL ausführen, Antwort mit
   `returned_deterministic_fallback: true`.
5. `result`-Event: `final_response` plus `thoughts` (mit Labels), `question_type`, `target_db`,
   `standalone_question`, `sql_column_types`.
6. `NodeException` → `error`-Event mit Knoten und Exception-Typ; `RecursionLimitException` →
   `error_type: GraphRecursionError`. Rückgabe `null`.
7. **Abbruch:** `CancellationToken.CancelledException` wird immer an den Aufrufer weitergereicht –
   egal ob das Token zwischen zwei Knoten geprüft wurde oder ein Knoten mitten in der
   SQL-Ausführung abgebrochen wurde (dann steckt sie in der `NodeException` und wird ausgepackt).
   Der Aufrufer sendet dafür ein einheitliches Event (`error_type: Cancelled` im Chat).

Bei `null` hat der Aufrufer (Chat, NL-Charts) das `error`-Event schon erhalten.

## Statische SQL-Prüfung (`SqlStaticChecks`)

`check(sql, CatalogView)` liefert `Result(errors, tables, columns)`:

1. `ReadOnlySql.violation` – nur ein rein lesendes `SELECT`/`WITH` (auch schreibende CTEs und
   `FOR UPDATE` in Unterabfragen werden abgelehnt, siehe [`ao-connectors`](ao-connectors.md)).
2. Parsen mit JSqlParser.
3. Jede referenzierte Tabelle existiert im Katalog der Datenbank (Schlüssel `schema.table`,
   case-insensitiv).
4. Spaltenreferenzen über Aliase auflösen; unbekannte Spalten melden. Ausgenommen sind
   Subquery-Aliase, SELECT-Aliase (auch ohne `AS`: `COUNT(*) n … ORDER BY n`) und die
   **Pseudospalten der Engine** (`PSEUDO_COLUMNS`: `SYSDATE`, `SYSTIMESTAMP`, `ROWNUM`, `ROWID`,
   `LEVEL`, `USER`, `CURRENT_DATE` …). JSqlParser meldet diese Namen als Spalten; ohne die Ausnahme
   würde korrektes Oracle-SQL mit „Column SYSDATE does not exist“ in die Reparaturschleife laufen.
5. Degenerierte Prädikate wie `1=0`.
6. PostgreSQL: Mixed-Case-Bezeichner müssen gequotet sein.

Die verwendeten Tabellen und Spalten gehen in den Intent-Prompt.

## Join-Pfade (`JoinPathFinder`)

- `graph(databaseId)`: ungerichteter Tabellengraph aus Semantic FKs mit `use_in_join_paths = true`
  und deklarierten FKs. Jede Kante (`Hop`) behält die exakten Join-Spalten; Selbstkanten und
  Duplikate entfallen.
- `bridge(db, from, to)`: kürzester Pfad (BFS) oder `null`.
- `connect(db, tableIds)`: kürzeste Pfade vom ersten zu allen anderen Tabellen, zusammengeführt;
  Zwischentabellen (Hubs, Bridges) gehören zum Ergebnis, unerreichbare werden gemeldet.
- Zwei Spalten, die nur auf dasselbe Zielattribut zeigen, werden **nicht** direkt verbunden – Kanten
  laufen immer zur Tabelle des Attributs.

## Entity Coverage

`EntityCoverageService.coverage(question, targetDb, maxDistance, …)`: Entitäten extrahieren, je
Entität Kandidaten suchen, Treffer mit Distanz ≤ `maxDistance` (Default 0,75) zählen, Abdeckung =
Anteil der Entitäten mit Treffer, Rückgabe mit gerankten Kandidaten je Entität. `domain_rules`
sind Custom Analyses plus Akronyme. Läufe sind serialisiert. Grundlage von
`POST /api/question-entity-coverage` und dem MCP-Tool `check_answerable`.

## Chart-Empfehlung (`ChartAdvisor`)

`recommend(question, sql, columns, jdbcTypes, rows)` fragt das LLM (`VisualizationRecommendation`),
ob ein Chart besser ist als eine Tabelle, und liefert dann
`{viz_type, params, mode: "sql"}`. Erlaubt sind nur Typen mit genau einer Query: `bar`, `line`,
`scatter`, `heatmap`, `big_number`, `table`. Spaltennamen werden gegen die Ergebnisspalten geprüft.
`coltype(jdbcType)` bildet JDBC-Typen auf die `coltypes` des Dashboards ab (0 numerisch, 1 Text,
2 zeitlich, 3 boolesch).

## Konfiguration

| Property | Default | Wirkung |
|---|---|---|
| `ao.agent.max-reconstruction-attempts` | 7 | Fehlversuche bis `unconstructable` |
| `ao.agent.recursion-limit` | 45 | Knotenbesuche je Durchlauf |
| `ao.agent.sql-gen-max-entities` (`SQL_GEN_MAX_ENTITIES`) | 5 | Entitäten je Frage |
| `ao.agent.max-result-rows` | 1000 | Zeilenlimit der Ausführung |
| `ao.agent.intent-validation-joins-validated-elsewhere` | `false` | wählt den Intent-Prompt ohne Join-Bewertung |
| `ao.agent.validate-sql-values`, `db-probe-*`, `detect-vacuous-group-by`, `prefer-certified`, `probe-budget` | `false` / 20 | vorhanden, aber ohne Wirkung (zugehörige Knoten nicht umgesetzt) |
| `ao.security.pii-samples-to-llm` | `false` | Beispielwerte von PII-Spalten im Prompt |
| Laufzeitschalter `sql_query_timeout_seconds` | 30 | Timeout der Ausführung |

## Erweiterungspunkte

**Neuen Knoten einfügen:**

1. Methode `public void myNode(AgentState st)` in `Nodes` (oder eigener Klasse); Ergebnis in
   `st.put(...)`, Trace mit `st.recordThought("my_node", "...")`.
2. In `AgentRunner.buildGraph` registrieren und Kanten/Router anpassen.
3. Label in `NodeLabels.LABELS` ergänzen (sonst zeigt die UI den Rohnamen).
4. Prompt als Ressource ablegen und mit `Prompts.render` füllen; Ausgabe als Record in
   `AgentModels` mit `@LlmField`.
5. Test mit `ScriptedLlmGateway`.

**Dialektregeln:** `prompts/dialect_<dialect>.txt`; der Dialektname kommt von
`SourceDatabase.dialect()`.

**Runner ersetzen:** eigene `AgentRunner`-Bean (z. B. mit zusätzlichen Knoten).

## Code-Beispiele

### Der Agent von außen

```java
List<Map<String, Object>> events = new ArrayList<>();
Map<String, Object> answer = runner.run(
        new AgentRunner.Request(
                "Top 5 customers by revenue in 2025",
                "Revenue means extended price minus discount.",   // evidence, optional
                null,                                             // prediction: null = das LLM klassifiziert
                "demo_sales",                                     // target_db: Name oder UUID, null = automatisch
                List.of(Map.of("question", "How many customers?", "answer", "663", "sql", "SELECT COUNT(*) …")),
                connections.answeringConnectors()),
        events::add,                                              // step-, sql-, result-, error-Events
        new CancellationToken());

if (answer == null) {
    // ein error-Event wurde bereits gesendet (Knotenfehler oder Rekursionslimit)
} else {
    answer.get("response");              // Antworttext mit [[[name/id]]]-Links
    answer.get("sql_code");              // das ausgeführte SQL ("" bei Metadatenfragen)
    answer.get("sql_response_from_db");  // List<Map<String, Object>> oder null
    answer.get("thoughts");              // [{node, label, thought}, …]
}
```

Die Events eines typischen Laufs:

```json
{"type":"step","node":"question_intent","label":"Classifying the question","phase":"start"}
{"type":"step","node":"question_intent","label":"Classifying the question","phase":"end","thought":"Question type: calculation (ranking)"}
{"type":"sql","node":"validate_sql_query","sql":"SELECT c.CUSTOMER_NAME, SUM(…) …"}
{"type":"result","answer":{"response":"…","sql_code":"…","sql_columns":["CUSTOMER_NAME","REVENUE"],"sql_response_from_db":[…],"thoughts":[…],"question_type":"calculation","target_db":"demo_sales"}}
```

### Einen Knoten ergänzen

Beispiel: nach der Ausführung prüfen, ob das Ergebnis abgeschnitten wurde, und das in der Antwort
vermerken.

```java
// Nodes.java
public void noteTruncation(AgentState st) {
    Integer rows = st.get("row_count");                       // von execute_sql_query gesetzt
    int max = s.props().agent().maxResultRows();
    if (rows != null && rows >= max) {
        st.put("truncation_note", "Only the first " + max + " rows are shown.");
        st.recordThought("note_truncation", "Result capped at " + max + " rows");   // erscheint als Step-Gedanke
    }
}
```

```java
// AgentRunner.buildGraph
g.node("note_truncation", nodes::noteTruncation);
// vorher: check_value_repair … default -> "format_and_respond"
g.route("check_value_repair", st -> switch (String.valueOf(st.<Object>get("value_repair"))) {
    case "repaired" -> "validate_sql_query";
    case "regenerate" -> st.failedAttempts() < maxAttempts ? "reconstruct_sql" : "note_truncation";
    default -> "note_truncation";
});
g.edge("note_truncation", "format_and_respond");
```

```java
// NodeLabels.LABELS
Map.entry("note_truncation", "Checking the result size")
```

Regeln für Knoten: nur über `st.get/put` kommunizieren; `st.put("error", …)` plus
`st.addFailedAttempt(sql, error)` signalisiert einen Fehlversuch (das Routing entscheidet dann über
`retryOr`); Exceptions nur werfen, wenn der Lauf wirklich enden soll (sie werden zum `error`-Event).

### Ein Knoten mit LLM-Aufruf und Fallback

```java
public void classifyUrgency(AgentState st) {
    String prompt = Prompts.render("urgency_check", Map.of("question", st.question()));
    UrgencyModel m = s.llm().tryStructured(ModelRole.NON_REASONING, List.of(LlmMessage.user(prompt)),
            UrgencyModel.class).orElse(null);
    // m == null: LLM nicht erreichbar oder Antwort ungültig → Fallback, kein Abbruch
    st.put("urgent", m != null && m.urgent());
    st.recordThought("classify_urgency", m == null ? "No classification available" : m.reasoning());
}
```

### Die statische Prüfung direkt verwenden

```java
SqlStaticChecks.CatalogView view = SqlStaticChecks.view(catalog.columnsOfDatabase(databaseId), "postgres");
SqlStaticChecks.Result r = SqlStaticChecks.check(
        "SELECT c.customer_name, SUM(o.amount) total FROM sales.orders o"
                + " JOIN sales.customers c ON c.id = o.customer_id GROUP BY c.customer_name ORDER BY total DESC", view);
r.valid();      // true
r.tables();     // [sales.orders, sales.customers]
r.columns();    // [sales.customers.customer_name, sales.orders.amount, sales.customers.id, sales.orders.customer_id]

SqlStaticChecks.check("SELECT o.totl FROM sales.orders o", view).errors();
// ["Column totl does not exist in table orders."]   ← genau dieser Text geht an reconstruct_sql
```

### Join-Pfade abfragen

```java
JoinPathFinder.Result joins = joinPathFinder.connect(databaseId, List.of(ordersId, citiesId));
joins.hops();        // [SALES.ORDERS.CUSTOMER_ID = SALES.CUSTOMERS.CUSTOMER_ID, SALES.CUSTOMERS.CITY_ID = SALES.CITIES.CITY_ID]
joins.tableIds();    // [orders, cities, customers]  – die Zwischentabelle CUSTOMERS gehört dazu
joins.unreachable(); // Tabellen ohne Pfad
joins.describe();    // der Block "SUGGESTED JOIN PATHS …" für den Prompt

List<JoinPathFinder.Hop> path = joinPathFinder.bridge(databaseId, ordersId, citiesId);   // null = kein Pfad
```

### Beantwortbarkeit und Chart-Empfehlung

```java
Map<String, Object> c = coverage.coverage("revenue by supplier", "demo_sales", 0.75, true);
c.get("coverage");            // 0.5 – Anteil der Entitäten mit Treffer
c.get("uncovered_entities");  // ["supplier"]

Optional<Map<String, Object>> chart = chartAdvisor.recommend(question, sql,
        List.of("MONTH", "REVENUE"), List.of(java.sql.Types.DATE, java.sql.Types.DECIMAL), rows);
// {viz_type=line, params={x_axis=MONTH, metrics=[REVENUE], row_limit=12, slice_name=…}, mode=sql}
```

## Tests

`SqlStaticChecksTest` prüft die statische Phase ohne LLM und ohne Store: verwendete Objekte,
unbekannte Tabellen und Spalten, Pseudospalten (`SYSDATE`, `ROWNUM`), Aliase ohne `AS`, die
Read-only-Regel inklusive schreibender CTEs, degenerierte Prädikate und das PostgreSQL-Quoting.

Der Agent als Ganzes wird Ende-zu-Ende in `DemoEndToEndTest` (`ao-app`) mit einem
`ScriptedLlmGateway` geprüft: Das erste SQL referenziert eine nicht existierende Tabelle
(statische Prüfung schlägt fehl → `reconstruct_sql`), das zweite filtert auf `'wholesale'`
(leeres Ergebnis → `check_value_repair` korrigiert auf `'Wholesale'`). Neue Knoten sollten eigene
Tests mit einem minimalen `AgentState` und `AgentServices` aus H2-Store und In-Memory-Vektorstore
bekommen.

## Fallstricke

- **Intent-Validierung ist „fail-open“:** Liefert das LLM keine gültige `IntentValidationModel`,
  gilt das SQL nach der statischen Prüfung als gültig.
- **`prediction: true`** setzt nur `question_type = prediction`; da es keinen Vorhersagezweig gibt,
  läuft die Frage durch den SQL-Pfad statt mit einem Fehler zu enden.
- Ein laufender LLM-Aufruf wird beim Abbrechen nicht unterbrochen; der Abbruch greift beim nächsten
  Knoten bzw. sofort bei laufendem JDBC-Statement.
- `constructSql` und `reconstructSql` nutzen `structured` (nicht `tryStructured`): Scheitert das LLM
  komplett, endet der Lauf mit einem `error`-Event statt mit `unconstructable`. Alle anderen Knoten
  haben einen Fallback.
- `validated_sql` wird zwischen Versuchen nicht geleert; das `sql`-Event erscheint nur bei einer
  Änderung.
- `executeSql` braucht den Konnektor der Zieldatenbank. Fehlt er (z. B. `target_db` zeigt auf eine
  Datenbank ohne antwortfähige Verbindung), endet der Lauf über die Fehlversuche mit
  `unconstructable`; nur wenn gar keine Zieldatenbank bestimmt wurde und genau ein Konnektor
  existiert, wird dieser verwendet.
- **Akronyme ohne Beschreibung** erscheinen im Prompt nur mit ihrem Namen (früher als
  `"- KPI: null"`).
- Viele Prompt-Texte sind im Java-Code zusammengesetzt (z. B. `reconstructSql`,
  `informationAgent`). Wer den Prompt sucht, muss also auch in `Nodes` und `AgentPrompts` schauen,
  nicht nur in `prompts/`.

## Anforderungsbezug

FR-AGT-01 bis FR-AGT-12 (ohne die nicht umgesetzten Knoten), FR-AGT-01a/b/c, NFR-SEC-01/04,
NFR-OBS-01, NFR-QD-08.
