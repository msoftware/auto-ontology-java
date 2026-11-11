# Modul `ao-api`

Pakete `de.jentsch.autoontology.api` und `…api.chat` · [Zurück zur Übersicht](../README.md)

## Zweck

Die **öffentliche REST-API** unter `ao.api-prefix` (Standard `/api`), die SSE-Chat-Laufzeit, die
internen Worker-Endpunkte unter `/internal/**` und das OpenAPI-Dokument (springdoc). Pfade,
Methoden und JSON-Felder entsprechen `auto-ontology/docs/openapi/auto-ontology-api.json` des
Originals.

## Abhängigkeiten

`api`: `ao-agent`, `jackson-dataformat-yaml` (Import/Export), `springdoc-openapi-starter-webmvc-api`.
Tests: `spring-boot-starter-webmvc-test`, H2.

## Klassen

| Klasse | Pfade (relativ zum Präfix) | Herkunft im Original | Fehlerstil |
|---|---|---|---|
| `CatalogController` | `/health`, `/connections/**`, `/datasources/dbs`, `/schemas/{db}`, `/tables/{schema}`, `/columns/{table}`, `/nodes/{id}`, `/semantic-compilation/{status,reset,trigger}`, `/question-entity-coverage` | FastAPI | `DETAIL` |
| `SemanticController` | `/terms/**`, `/sql-attributes/**`, `/custom-analyses/**`, `/pql-analyses/**` | FastAPI | `DETAIL` |
| `GovernanceController` | `/search/**`, `/tags/**`, `/rules/**`, `/zones/**`, `/groups/**`, `/owners` | FastAPI | `DETAIL` |
| `ExplorationController` | `/exploration/**` | FastAPI | `DETAIL` |
| `ModelInterchangeController` | `/model/export`, `/model/import` | FastAPI | `DETAIL` |
| `AppController` | `/conversations/**`, `/acronyms/**`, `/custom-prompts/**`, `/configurations/*`, `/analytics` | Next.js | `ERROR` / `PLAIN` |
| `chat.ChatController` | `/chat/completions`, `/chat/watch`, `/chat/cancel` | FastAPI (SSE) | `DETAIL` |
| `chat.ChatService` | – | `server/chat/router.py`, `conversation_dal.py` | – |
| `InternalController` | **feste** Pfade `/health`, `/health/live`, `/internal/**` | Ingestion-Dienst | `DETAIL` |
| `ApiAutoConfiguration` | – | – | – |

## Beans

`ApiAutoConfiguration` (nach `AgentAutoConfiguration`) importiert alle Controller und definiert:

| Bean | Zweck |
|---|---|
| `aoChatService` | `ChatService` (`@ConditionalOnMissingBean`) |
| `aoNoStoreFilter` | Servlet-Filter auf `<ao.api-prefix>/*` und `<nl-chart-prefix>/*`: `Cache-Control: private, no-store` für alle Antworten außer SSE (FR-API-03) |

## Implementierungsdetails

### Präfix

Jeder Controller trägt `@RequestMapping("${ao.api-prefix:/api}")` (Exploration:
`…/exploration`, Import/Export: `…/model`) und mappt nur den Restpfad. Das Präfix steht an keiner
anderen Stelle im Code. Die Prüfung beim Start erledigt `ao-common`.

### Request- und Response-Formen

Controller nehmen überwiegend `@RequestBody Map<String, Object>` und geben Maps aus den
Repositories zurück. Das hält die JSON-Felder identisch zum Original (snake_case), ohne DTOs. Listen
haben meist die Form `{"data": [...], "count": n}` bzw. bei Paginierung `{"data", "total", …}`.
Ungültige UUIDs in Pfaden führen zu 404 (nie 500).

### Chat-Laufzeit (`ChatService`)

```
completions(req, source)
 ├─ question leer → 422; conversation_id kein UUID → 422
 ├─ laufender Run derselben Konversation:
 │     Client verbunden → 409 "Conversation in progress"
 │     verwaist        → alten Run abbrechen (token.cancel), neuer Run übernimmt
 ├─ subscribe(run, owner=true)  → SseEmitter (Timeout 30 min), Ping-Kommentar alle 15 s
 └─ Virtual Thread "ao-chat-<id>": execute(run, req, source)
       ├─ token.bind()
       ├─ prepareConversation (anlegen, Historie = abgeschlossene Runden, User-Nachricht, touch)
       ├─ Analytics-Zeile (Benutzer, Konversation, Quelle, Frage)
       ├─ keine Konnektoren → error-Event "No database connection is configured."
       ├─ AgentRunner.run(…, event -> emit(run, event), run.token)
       ├─ persistAssistant: Assistenten-Nachricht (Text + SQL), Analytics vervollständigen (inkl. Trace)
       ├─ mit conversation_id: chartsStep → ChartAdvisor (wenn visualization_enabled) → charts-Event,
       │     zweite Assistenten-Nachricht (```chart-Blöcke oder Zeilen als JSON)
       └─ finally: Scope schließen, finish(run): [DONE] an alle Abonnenten, Run entfernen
```

- **Puffer:** `emit` hängt jedes Event an `run.buffer` und sendet es an alle Abonnenten.
  `watch(conversationId)` abonniert einen laufenden Run, spielt zuerst den gesamten Puffer ab und
  bekommt danach Live-Events; läuft nichts, endet der Stream sofort mit `[DONE]`.
- **Verbindungsstatus:** Schließt der Client des Owner-Emitters (Completion, Timeout, Fehler), wird
  `clientConnected = false`. Der Lauf läuft weiter und speichert sein Ergebnis.
- **Cancel:** `cancel(conversationId)` ruft `token.cancel()`; JDBC-Statements werden abgebrochen, der
  Graph stoppt vor dem nächsten Knoten, der Client bekommt `error` mit `error_type: Cancelled`.
- **SSE-Format:** `data: <json>` je Event, abschließend `data: [DONE]`. Die Daten werden mit
  `MediaType.TEXT_PLAIN` und führendem Leerzeichen geschrieben, damit das Format exakt dem
  Original entspricht.
- **Quelle:** Header `x-auto-ontology-source` (Default `api`), landet in
  `app_conversation_analytics.source`.

### Wichtige Sonderfälle der Routen

| Route | Verhalten |
|---|---|
| `GET /health` (öffentlich) | `{status: ok|degraded, store, vector_store, llm_configured}` – nie 500 |
| `POST /connections` | speichert die UI-Verbindung, startet sofort einen nicht-blockierenden Ingest |
| `DELETE /connections/{name}` | löscht Katalog und Embeddings, gibt den Namen zurück |
| `POST /connections/test` | prüft ohne zu speichern und liefert die Schemas |
| `GET /connections` | ohne Credentials |
| `PATCH /nodes/{id}` | ermittelt die Tabelle (`catalog_database`…`catalog_column`) über die ID; nur `description`, `description_certified`, `owner_id` (nicht bei Spalten), `sample_values` (nur Text-/JSON-/XML-Spalten); setzt `modified`/`modified_by`; Spalten werden neu eingebettet; eine nicht gespeicherte Änderung ist ein Fehler, nie ein stilles 200 |
| `PATCH /terms/{id}` | nur `name`, `description`, `name_certified`, `description_certified`, `owner_id`; bettet den Term neu ein; Antwort mit `term_certification` |
| `POST /sql-attributes` | 201; Pflicht `name`, `description`, `expression`, `term_id` |
| `POST /sql-attributes/validate`, `/custom-analyses/validate` | Parse-/Auflösungsfehler sind **422**, nicht `valid: false` |
| `PUT /sql-attributes/{id}` | ersetzt den Ausdruck, parst neu, setzt `description_suggestion` zurück, bettet neu ein |
| `PATCH /sql-attributes/{id}` | nur Metadaten, nie der Ausdruck |
| `DELETE /sql-attributes/{id}` | löscht Kanten und Embedding |
| `GET /sql-attributes/{id}/description-suggestion` | LLM-Vorschlag, im Attribut gecacht |
| `POST /custom-analyses` | 409 bei Namens- oder SQL-Konflikt |
| `POST /semantic-compilation/reset` | 202; Reset läuft asynchron |
| `PUT /configurations/semantic-compilation` | `{"enabled": true}` speichert und stößt einen Lauf an |
| `POST /model/export` | Body `{format: "auto_ontology", databases: [ids]}`; YAML mit Datenbanken, Tabellen, Spalten, Terms, Attributen, SqlAttributes, Analysen |
| `POST /model/import` | Multipart `file`, `replace` (Default `true`); Zuordnung über `imported_id` oder `id`; Ossie-Dokumente (`semantic_model`/`ossie`) → 422 |
| `GET /exploration/tables/zones` | `null` = keine Einschränkung, leere Liste = keine Treffer (FR-AUTH-09) |

### Interne Endpunkte (`InternalController`)

Feste Pfade ohne Präfix, gedacht für den Worker-Betrieb und das private Netz:

| Pfad | Aktion |
|---|---|
| `GET /health`, `GET /health/live` | Readiness (wie `/api/health`) und Liveness (`{"status":"ok"}`) |
| `POST /internal/ingest` | `IngestScheduler.trigger()` |
| `POST /internal/ingest/delete` | `{"database_name"}` löschen |
| `POST /internal/semantic/compile` | Kompilierung triggern |
| `POST /internal/semantic/reset` | `{"database_name"?}`, asynchron |
| `GET /internal/semantic/status` | Kompilierungsstatus |
| `POST /internal/semantic/stop` | kooperativer Abbruch |
| `POST /internal/vector/reconcile` | Data Layer gegen den Store abgleichen: verwaiste Points löschen, bei fehlenden die Datenbank neu einbetten |

Mit `ao.internal.secret` verlangen die `/internal/**`-Routen den Header `x-ao-internal-secret`;
ein falscher oder fehlender Wert ergibt **404** (die Endpunkte sollen unsichtbar bleiben).

## Erweiterungspunkte

- **Neuer Endpunkt:** im passenden Controller, mit `@RequestMapping("${ao.api-prefix:/api}")`.
  Fehler über `ApiException` im Stil der Gruppe. Neue Pfade, die es im Original nicht gibt, in die
  Allowlist von `OpenApiContractTest` (`ao-app`) eintragen.
- **Eigene Chat-Laufzeit:** `ChatService`-Bean ersetzen.
- **Metriken:** hier wäre der Ort, `LlmMetrics.setListener` an eine `MeterRegistry` zu hängen (siehe
  [`ao-llm`](ao-llm.md)).

## Tests

Die API wird über `ao-app` getestet:

- `OpenApiContractTest` – generiertes OpenAPI (`/v3/api-docs`) gegen die Referenz des Originals
  (Pfade und Methoden, relativ zum Präfix; Allowlist für entfallene Anmelde-Endpunkte und
  Erweiterungen),
- `CustomApiPrefixTest` – alle Endpunkte unter `/ao/…`, `/api/…` liefert 404, ungültige Präfixe
  brechen den Start ab,
- `DemoEndToEndTest` – Status, Terms und ein Chat-Lauf über HTTP inklusive SSE.

## Fallstricke

- Die Controller sind breit (bis ~30 KB) und enthalten neben Mapping auch Logik (Validierung,
  Re-Embedding, Zertifizierungsberechnung). Neue Logik besser in Services der Fachmodule legen.
- `patchNode` erzeugt SQL mit dem ermittelten Tabellennamen und festen Spaltennamen. Neue Felder nur
  über die vorhandene Whitelist ergänzen.
- `/internal/**` ist **ohne** `ao.internal.secret` offen. Im Monolithen ist das egal, im
  Worker-Betrieb muss das Secret gesetzt sein.
- Reconcile behandelt nur den Data Layer und läuft nicht automatisch nach Ingest oder Kompilierung.
- `watch` setzt `clientConnected = true`. Eine neue Completion für dieselbe Konversation wird danach
  wieder mit 409 abgelehnt, solange der Watcher verbunden ist.
- `watch` spielt den Puffer unter `synchronized (run)` ab, `emit` synchronisiert aber nicht auf den
  Run. Ein Event, das genau zwischen Puffer-Replay und Anmeldung des Abonnenten entsteht, kann dem
  Watcher fehlen (kleines Zeitfenster).
- `ChatService` hält laufende Runs nur im Speicher; ein Neustart beendet sie (gespeichert ist dann
  nur die User-Nachricht).

## Anforderungsbezug

FR-API-01 bis FR-API-04, FR-CHAT-01 bis FR-CHAT-05, FR-CON-06, FR-ING-04, FR-SEM-07/08,
FR-GOV-01 bis FR-GOV-12, FR-AUTH-07 bis -09, NFR-ARC-03/05, NFR-SEC-03, NFR-OBS-02, NFR-TST-03/07.
