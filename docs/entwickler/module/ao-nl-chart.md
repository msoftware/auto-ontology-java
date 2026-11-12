# Modul `ao-nl-chart`

Paket `de.jentsch.autoontology.nlchart` · [Zurück zur Übersicht](../README.md)

## Zweck

**Charts aus natürlicher Sprache für quasar-dashboard.** Eine Frage wird nicht in SQL, sondern in
`ChartParams` eines registrierten Dashboard-Datasets übersetzt (Modus A). Das Ergebnis ist ein
normaler Dashboard-Chart, den die Dashboard-API speichern kann. Passt kein Dataset, liefert der
Text-to-SQL-Agent Zeilen, die als `QueryResult` nur angezeigt werden (Modus B).

Das Modul ist **optional** und nicht im Starter enthalten, weil es `quasar-dashboard-backend` im
selben Host voraussetzt.

## Abhängigkeiten

`api`: `ao-api` (und damit Agent, Semantik, Ingest …), `de.jentsch.quasardashboard:quasar-dashboard-backend`
(Composite Build aus `../quasar-dashboard/backend`). Genutzte Dashboard-Klassen:
`DatasetRepository` (Datasets, Spalten, Metriken), `ChartDataService` (Kompilierung und
Spaltenwerte), `TimeRange` (Zeitgrammatik).

Ressource: `nl-chart/viz-catalog.json`.

## Klassen

| Klasse | Aufgabe |
|---|---|
| `NlChartAutoConfiguration` | Beans; hängt den Dataset-Sync an Ingest, Kompilierung und Anwendungsstart |
| `NlChartController` | REST unter `ao.nl-chart.api-prefix`: `/suggest` (SSE), `/validate`, `/datasets`, `/sync`, `/viz-catalog` |
| `NlChartService` | Pipeline: Kandidaten, Modus A mit Reparaturrunden, Modus B, Analytics |
| `DatasetSync` | Brücke Ontologie ↔ Datasets: Links, Spaltenrollen, Beschreibungen, Metriken als SqlAttributes, Dataset-Embeddings |
| `ChartValidator` | Validierung in vier Schritten plus Filterwert-Prüfung |
| `QueryContextBuilder` | Java-Port von `plugin.buildQuery` der 8 eingebauten Viz-Typen |
| `SqlResults` | Agent-Zeilen → `QueryResult` des Dashboards |

## Beans

| Bean | Typ | Hinweis |
|---|---|---|
| `aoDatasetSync` | `DatasetSync` | registriert sich als `IngestService.Stage` (`afterIngest`) und als `onRunCompleted`-Hook der Kompilierung |
| `aoChartValidator` | `ChartValidator` | nutzt `ChartDataService` des Dashboards |
| `aoNlChartService` | `NlChartService` | |
| `aoDatasetSyncAtStart` | `ApplicationListener<ApplicationReadyEvent>` | ein Sync nach dem Start; Fehler nur geloggt |

## Abläufe

### Dataset-Sync

```
DatasetSync.sync()   (synchronized)
 für jedes qd_dataset (gefiltert über ao.nl-chart.dataset-allowlist):
  ├─ Dashboard-Datenbank → AO-Datenbank über ao.dashboard.database-mapping (n:1 erlaubt)
  ├─ Katalogtabelle (schema + table) finden → ao_dataset_link(dataset_id, table_id, synced_at, warnings)
  ├─ Beschreibungen übernehmen (Dataset → Tabelle, Spalte → Spalte), nur wenn nicht zertifiziert
  ├─ je Spalte: ao_dataset_column_link(type_generic, is_dttm, groupby, filterable, column_id)
  ├─ je gespeicherter Metrik: SqlAttribute source='dataset' am Term der Tabelle anlegen/aktualisieren
  │     (zertifizierte bzw. fremde Attribute werden nicht überschrieben; ist der Name schon von einer
  │      anderen Metrik oder einem anderen Attribut belegt: "<Name> (<Tabelle>)")
  │     neu angelegte und geänderte Attribute werden eingebettet; ein Embedding-Fehler ist eine Warnung
  └─ Embedding label='Dataset' in semantic_layer: Name, Beschreibung, Terms + Synonyme, Spalten, Metriken
 Warnungen (Dataset ohne Tabelle, Spalten nicht im Katalog) → lastStatus()
```

Die Spaltenrollen liegen bewusst in einer eigenen Tabelle und nicht am ColumnAttribute: FK-Spalten
haben kein ColumnAttribute, sind aber Dataset-Spalten.

**Metriknamen sind nicht eindeutig.** `sql_attribute.name` ist im ganzen Store eindeutig, eine
Metrik wie `count` gibt es aber in fast jedem Dataset. Der Sync erkennt „seine“ Spiegelung an der
Quelle `dataset` plus dem Beschreibungssuffix ` (dataset <Tabelle>, metric <Name>)` bzw. dem Term
(`mirrorOf`). Ist der Name anderweitig vergeben, heißt das Attribut `<Name> (<Tabelle>)`:

| Dataset | Metrik | SqlAttribute |
|---|---|---|
| `sales` | `count` | `count` |
| `web_traffic` | `count` | `count (web_traffic)` |
| `sales` | `Total revenue` (verbose name) | `Total revenue` |

Ohne diese Regel überschrieben sich gleichnamige Metriken bei jedem Sync gegenseitig. Geschrieben
(und neu eingebettet) wird nur, wenn sich Ausdruck oder Beschreibung wirklich geändert haben;
Metriken ohne Ausdruck werden mit Warnung übersprungen.

### Vorschlag (`NlChartService.suggest`)

1. Analytics-Zeile mit Quelle `nl-chart`.
2. **Kandidaten:** mit `dataset_id` genau dieses Dataset; sonst Vektorsuche in `semantic_layer`
   (Label `Dataset`, k = 5, Distanz ≤ 0,75). Ohne Treffer (oder ohne Embeddings) sind **alle**
   erlaubten Datasets Kandidaten.
3. **Modus A:** `ChartSuggestion` von der REASONING-Rolle. System-Prompt mit Datum, Regeln zur
   Typwahl (FR-NLC-08), Zeitgrammatik und Metrik-Vorrang; User-Prompt mit Kandidaten (Spalten mit
   Rollen, Metriken, optional deren SQL), gewünschtem `viz_type`, `current_params` (Verfeinerung als
   Diff) und der Frage.
   - `dataset_id = null` → Modus B.
   - `params_json` (ChartParams als JSON-**String**, weil das strikte Schema kein freies Objekt
     zulässt) parsen und `ChartValidator.validate(…, checkValues = true)`. Ein fehlender `viz_type`,
     ein Dataset außerhalb der Kandidaten und ungültiges JSON sind ebenfalls Fehler.
   - Fehler → Reparaturrunde: bisherige Antwort als Assistant-Nachricht, Fehlerliste als User-
     Nachricht. Höchstens 2 Runden (`MAX_REPAIR_ROUNDS`), danach `error`-Event – **und Ende**.
     Erster Versuch und Reparaturrunden laufen durch dieselbe Schleife (gleiche Prüfungen, gleiche
     `validate`-/`repair`-Events).

   Die drei Ausgänge von Modus A:

   | Ergebnis | Bedeutung | Danach |
   |---|---|---|
   | Antwort-Map | gültiger Vorschlag | `chart_suggestion` |
   | `null` | kein Dataset passt (`dataset_id: null`) oder das LLM lieferte nichts | Modus B |
   | `FAILED` | Vorschlag blieb nach 2 Reparaturen ungültig, `error` ist gesendet | nichts mehr |

   Früher fiel auch der dritte Fall in Modus B: Nach der Fehlermeldung lief dann noch der ganze
   SQL-Agent und schickte eine zweite, widersprüchliche Antwort hinterher.
4. **Modus B:** `AgentRunner.run` mit `prediction = false`; `step`- und `error`-Events werden
   durchgereicht. Aus den Zeilen macht `ChartAdvisor` einen Chart (sonst `table` mit
   `query_mode: raw`), `SqlResults.toQueryResult` das `QueryResult`. Warnung „not savable as a
   dashboard chart“.
5. `chart_suggestion`-Event; Analytics mit Erklärung und Params vervollständigen.

### Validierung (`ChartValidator.validate`)

1. **Viz-Katalog:** Typ bekannt, Pflicht-Controls vorhanden; `withDefaults` ergänzt Defaults des
   Plugins und die gemeinsamen Controls (wie `useExploreStore.init`).
2. **Dataset:** Spalten und Metriken existieren, Rollen passen (gruppierbar, filterbar, zeitlich,
   numerisch); Ad-hoc-Metriken nur `SIMPLE` mit `COUNT`, `COUNT_DISTINCT`, `SUM`, `AVG`, `MIN`,
   `MAX`; Filteroperatoren nur aus der Vertragsliste; `time_range` mit `TimeRange.parse(expr, now)`.
3. **`query_context`** mit `QueryContextBuilder.build` (für unbekannte Typen `null` → Warnung, nur
   clientseitige Prüfung).
4. **Kompilierung** durch die Dashboard-Engine (`ChartDataService`, `result_type: query`).

Die Params stammen vom LLM und sind **nicht vertrauenswürdig geformt**: `adhoc_filters`, das keine
Liste ist, oder ein Filter, der kein Objekt ist, ergibt einen Validierungsfehler (und damit eine
Reparaturrunde) statt einer `ClassCastException`.

Filterwerte (`==`, `!=`, `IN`, `NOT IN` auf Textspalten) werden gegen die echten Spaltenwerte
geprüft (`values(dataset, column)` über die Dashboard-Engine). Abweichungen in Groß-/Kleinschreibung
und Tippfehler werden ersetzt und als `assumption` gemeldet, unbekannte Werte als Warnung.

### `QueryContextBuilder`

Arbeitet auf der JSON-Form von `ChartParams` und `QueryObject`. Unterstützt `bar`, `line`, `pie`,
`big_number`, `scatter`, `heatmap`, `pivot_table`, `table` und bildet die Logik aus
`packages/dashboard/src/viz/*/buildQuery.ts`, `viz/shared/query.ts` und `utils/pivot.ts` nach.
`pie`, `pivot_table` und `table` mit `show_totals` erzeugen mehrere Queries – deshalb sind sie in
Modus B nicht erlaubt.

## API

| Endpunkt | Body / Antwort |
|---|---|
| `POST /suggest` (SSE) | `{question, dataset_id?, viz_type?, current_params?, conversation_id?, dashboard_id?}` → `step`-Events, `chart_suggestion` oder `error`, `[DONE]`; Emitter-Timeout 10 min |
| `POST /validate` | `{dataset_id, viz_type, params}` → `{valid, errors, warnings, query_context}` (ohne LLM, ohne Wertprüfung) |
| `GET /datasets` | `{data: [Abdeckung je Dataset], count, sync: lastStatus}` |
| `POST /sync` | `{data: Ergebnis des Syncs}` |
| `GET /viz-catalog` | Inhalt von `viz-catalog.json` |

Gespeichert wird **nie** hier, sondern über `POST /api/v1/chart/` der Dashboard-API.

## Konfiguration

| Property | Default | Wirkung |
|---|---|---|
| `ao.nl-chart.api-prefix` | `${ao.api-prefix}/nl-chart` | Präfix; darf nicht unter `quasar-dashboard.api-prefix` liegen |
| `ao.nl-chart.dataset-allowlist` | leer (alle) | Datasets, die überhaupt betrachtet werden |
| `ao.nl-chart.expose-metric-sql` | `false` | SQL gespeicherter Metriken im Prompt |
| `ao.dashboard.database-mapping` | leer | `qd_database.database_name` → AO-Datenbankname |

## Erweiterungspunkte

- **Neuer Viz-Typ des Hosts:** in `viz-catalog.json` beschreiben; ohne Port in
  `QueryContextBuilder` wird er nur clientseitig geprüft.
- **Prompt-Regeln:** `NlChartService.systemPrompt` / `userPrompt`.
- **Golden Set:** `demo/nl-chart-questions.yaml` erweitern und gegen ein echtes LLM laufen lassen.

## Code-Beispiele

### Der Stream eines Vorschlags

```bash
curl -N -X POST localhost:8080/api/nl-chart/suggest -H 'Content-Type: application/json' \
     -d '{"question":"Monthly revenue by region last year as a line"}'
```

```json
data: {"type":"step","node":"choose_dataset","label":"Choosing dataset","phase":"start"}
data: {"type":"step","node":"choose_dataset","label":"Choosing dataset","phase":"end","thought":"sales"}
data: {"type":"step","node":"build_chart","label":"Building chart","phase":"end","thought":"Revenue per month, one line per region"}
data: {"type":"step","node":"validate","label":"Validating","phase":"end","thought":"valid"}
data: {"type":"chart_suggestion","mode":"semantic","dataset_id":1,"viz_type":"line",
       "params":{"viz_type":"line","datasource":"1__table","x_axis":"order_date","time_grain_sqla":"P1M",
                 "metrics":["Total revenue"],"series":"region","time_range":"Last year","adhoc_filters":[]},
       "slice_name":"Monthly revenue by region","explanation":"…","assumptions":["Revenue = saved metric Total revenue"],
       "alternatives":[{"viz_type":"bar","reason":"…"}],"query_context":{…},"warnings":[]}
data: [DONE]
```

Verfeinern heißt: dieselbe Route mit den aktuellen Params aufrufen.

```json
{"question":"only Germany, per week","dataset_id":1,
 "current_params":{"viz_type":"line","x_axis":"order_date","metrics":["Total revenue"],"series":"region"}}
```

### Params ohne LLM prüfen

```bash
curl -X POST localhost:8080/api/nl-chart/validate -H 'Content-Type: application/json' -d '{
  "dataset_id": 1, "viz_type": "bar",
  "params": {"x_axis": "region", "metrics": ["Total revenue"],
             "adhoc_filters": [{"col": "country", "op": "==", "val": "Germany"}]}}'
# {"valid":true,"errors":[],"warnings":[],"query_context":{"datasource":{"id":1,"type":"table"},"queries":[…]}}
```

```java
ChartValidator.Result r = validator.validate(dataset, "bar",
        Map.of("x_axis", "regoin", "metrics", List.of("Total revenue")), false);
r.valid();        // false
r.errors();       // ["INVALID_COLUMN_ERROR: column regoin (x_axis) does not exist in dataset sales"]
r.params();       // die um Defaults ergänzten Params
r.queryContext(); // null bei Fehlern, sonst der Kontext für result_type "full"
```

### Was aus Params an die Engine geht

```java
Map<String, Object> params = new LinkedHashMap<>();
params.put("viz_type", "line");
params.put("x_axis", "order_date");
params.put("granularity_sqla", "order_date");      // x_axis == Zeitspalte → Zeitreihe
params.put("time_grain_sqla", "P1M");
params.put("metrics", List.of("Total revenue"));
params.put("series", "region");
params.put("row_limit", 10000);

List<Map<String, Object>> queries = QueryContextBuilder.queries(params);
// [{columns=[order_date, region], metrics=[Total revenue], filters=[], granularity=order_date,
//   time_range=No filter, time_grain=P1M, orderby=[[order_date, true]], row_limit=10000, is_timeseries=true}]

QueryContextBuilder.queries(Map.of("viz_type", "sunburst"));   // null: Host-Plugin, nur clientseitig geprüft
```

### SQL-Zeilen als `QueryResult` (Modus B)

```java
Map<String, Object> result = SqlResults.toQueryResult(
        List.of("MONTH", "REVENUE"), List.of(java.sql.Types.DATE, java.sql.Types.DECIMAL),
        List.of(Map.of("MONTH", "2025-06-01", "REVENUE", new BigDecimal("1234.5600000000000001"))),
        "SELECT …", time.now());
// colnames=[MONTH, REVENUE], coltypes=[2, 0]
// data=[{MONTH=1748736000000, REVENUE=1234.56}]   – Datum als Epoch-Millisekunden UTC, 15 signifikante Stellen
```

`NaN` und Unendlich werden zu `null` (JSON kennt sie nicht); ganze Zahlen außerhalb des
`long`-Bereichs bleiben Gleitkommawerte, statt überzulaufen.

### Dataset-Sync auslösen und lesen

```bash
curl -X POST localhost:8080/api/nl-chart/sync
# {"data":{"synced_at":"2026-06-30T00:00:00Z","datasets_linked":4,"metrics":9,
#          "warnings":["inventory: table has no Term yet; metrics are not mirrored"]}}
curl -s localhost:8080/api/nl-chart/datasets
# {"data":[{"id":1,"table_name":"sales","table_id":"…","terms":["Sale"],"metrics":3,"vector_indexed":true,…}],…}
```

## Tests

- `QueryContextBuilderTest` – Zeitreihe nach Zeitspalte sortiert, Einzel-Query-Konfigurationen für
  Modus B, Umwandlung von SQL-Zeilen in ein `QueryResult`.
- `NlChartIntegrationTest` (`ao-dashboard-demo`) – Dataset-Sync, Vorschlag mit gestubbtem LLM und
  Validierung gegen die echte Dashboard-Engine.

## Fallstricke

- **Modus B ist selten:** Weil ohne Vektortreffer alle Datasets Kandidaten sind, läuft Modus B nur,
  wenn das LLM ausdrücklich `dataset_id: null` liefert (oder gar nicht antwortet). Ein Vorschlag, der
  an der Validierung scheitert, endet mit `error` – ohne Modus B.
- **Umbenannte Metrik-Spiegel:** Gleichnamige Metriken mehrerer Datasets heißen ab dem zweiten
  Dataset `<Name> (<Tabelle>)`. Welches Dataset den „nackten“ Namen bekommt, hängt von der
  Reihenfolge des ersten Syncs ab (aufsteigende Dataset-ID).
- `conversation_id` wird nur für Analytics gespeichert; Folgefragen funktionieren nur über
  `current_params`.
- Das Gesamtbudget aus NFR-NLC-02 (z. B. 60 s, höchstens 8 LLM-Aufrufe) ist nicht umgesetzt.
- Modus B läuft ohne `CancellationToken`; ein abgebrochener Client beendet den Agentenlauf nicht.
- Der Viz-Katalog ist handgeschrieben; ändert quasar-dashboard die Controls eines Typs, muss die Datei
  nachgezogen werden.
- Modus A funktioniert nur auf Datasets in PostgreSQL und H2, bis quasar-dashboard Oracle- und
  SQL-Server-Dialekte bekommt (FR-NLC-24).

## Anforderungsbezug

FR-NLC-01 bis FR-NLC-14, Abschnitt 23.6, FR-NLC-23, NFR-QD-04, NFR-QD-08, NFR-NLC-01.
