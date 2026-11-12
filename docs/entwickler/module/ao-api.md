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

**Eingaben werden vor dem Schreiben geprüft.** Weil die Bodies untypisierte Maps sind, fängt keine
Bean-Validation falsche Werte ab. Die Controller prüfen deshalb selbst, was sonst erst die Datenbank
als Constraint-Verletzung (und damit als 500) melden würde:

| Eingabe | Antwort |
|---|---|
| leerer oder fehlender Pflichtname (`name: null`, `""`) bei Term, Attribut, SQL Attribute, Zone, PQL Analysis | 422 |
| Name, den ein anderes Objekt schon trägt (Term je `source`, SQL Attribute, Tag, Gruppe, Akronym) | 409 |
| `certified`, `name_certified`, `description_certified`, `enabled` kein Boolean | 422 |
| `source` eines SQL Attributes außerhalb von `manual`, `sql`, `table`, `bridgeTable`, `dataset` | 422 |
| Zonen-Item mit unbekanntem Typ oder nicht existierender ID | 422 |
| `skip`/`limit` negativ | wird auf 0 begrenzt |
| Verbindung ohne `url`, `host`/`database` oder `datasource_bean` | 422 |

JSON-`null` wird nie zum Text `"null"`: Textfelder liest man mit `AppController.str` (leer →
`null`) bzw. `AppController.text`.

### Chat-Laufzeit (`ChatService`)

```
completions(req, source)
 ├─ question leer → 422; conversation_id kein UUID → 422
 ├─ laufender Run derselben Konversation:
 │     Client verbunden → 409 "Conversation in progress"
 │     verwaist        → alten Run abbrechen (token.cancel), neuer Run übernimmt
 │     (Prüfen und Ersetzen unter synchronized(runs): zwei gleichzeitige Requests starten nie beide)
 ├─ subscribe(run)  → SseEmitter (Timeout 30 min), Ping-Kommentar alle 15 s
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
- **Ein Monitor je Run:** `emit`, `finish` und der Replay in `watch` laufen unter
  `synchronized (run)`. Damit sind Replay und Anmeldung ein Schritt – ein Watcher verpasst kein
  Event und bekommt auch dann ein `[DONE]`, wenn der Lauf genau während des Anhängens endet (sonst
  hinge sein Stream bis zum Timeout von 30 Minuten).
- **Verbindungsstatus:** `clientConnected` ist wahr, solange **mindestens ein** Abonnent (der
  anfragende Client oder ein Watcher) verbunden ist. Jeder Abonnent bekommt denselben
  Lebenszyklus (`attach`): Ping alle 15 s, Abmelden bei Completion, Timeout oder Fehler. Der Lauf
  läuft auch ohne Abonnenten weiter und speichert sein Ergebnis.
- **Ein Callback je Emitter:** `SseEmitter.onCompletion` *ersetzt* einen früher gesetzten Callback.
  Aufräumen und Ping-Stopp stehen deshalb in einem einzigen Callback.
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
| `DELETE /connections/{name}` | löscht semantische Schicht, Katalog und Embeddings der Datenbank, gibt den Namen zurück |
| `POST /connections/test` | prüft ohne zu speichern und liefert die Schemas |
| `GET /connections` | ohne Credentials |
| `PATCH /nodes/{id}` | ermittelt die Tabelle (`catalog_database`…`catalog_column`) über die ID; nur `description`, `description_certified`, `owner_id` (nicht bei Spalten), `sample_values` (nur Text-/JSON-/XML-Spalten); setzt `modified`/`modified_by`; Spalten werden neu eingebettet; eine nicht gespeicherte Änderung ist ein Fehler, nie ein stilles 200 |
| `PATCH /terms/{id}` | nur `name`, `description`, `name_certified`, `description_certified`, `owner_id`; leerer Name → 422, vergebener Name → 409; bettet den Term neu ein; Antwort mit `term_certification` |
| `POST /sql-attributes` | 201; Pflicht `name`, `description`, `expression`, `term_id` |
| `POST /sql-attributes/validate`, `/custom-analyses/validate` | Parse-/Auflösungsfehler sind **422**, nicht `valid: false` |
| `PUT /sql-attributes/{id}` | ersetzt den Ausdruck, parst neu, setzt `description_suggestion` zurück, bettet neu ein; vergebener Name → 409 |
| `PATCH /sql-attributes/{id}` | nur Metadaten, nie der Ausdruck; vergebener Name → 409 |
| `DELETE /sql-attributes/{id}` | löscht Kanten und Embedding |
| `GET /sql-attributes/{id}/description-suggestion` | LLM-Vorschlag, im Attribut gecacht; antwortet das Modell nicht oder leer → **502** |
| `POST /custom-analyses` | 409 bei Namens- oder SQL-Konflikt |
| `PUT /custom-analyses/{id}` | dieselbe Read-only-Prüfung wie beim Anlegen (422 bei DML), 409 bei Konflikt |
| `PUT /pql-analyses/{id}` | `name` ist Pflicht (422) |
| `POST /semantic-compilation/reset` | 202; Reset läuft asynchron. Ohne `database_name` werden **alle** Datenbanken zurückgesetzt; ein unbekannter Name ist **404** und setzt nichts zurück |
| `PUT /configurations/semantic-compilation` | `{"enabled": true}` speichert und stößt einen Lauf an |
| `POST /model/export` | Body `{format: "auto_ontology", databases: [ids]}`; YAML mit Datenbanken, Tabellen, Spalten, Terms, Attributen, SqlAttributes, Analysen |
| `POST /model/export` (Details) | fehlende Werte (Beschreibung, Dialekt, Quelle) stehen als YAML-`null` im Dokument, nicht als Text `"null"` |
| `POST /model/import` | Multipart `file`, `replace` (Default `true`); Zuordnung über `id`, sonst über den Namen; leere Datei, Term ohne Namen oder Ossie-Dokumente (`semantic_model`/`ossie`) → 422; SQL Attributes ohne Namen oder Ausdruck werden übersprungen, eine unbekannte `source` wird zu `manual` |
| `GET /exploration/tables/zones` | `null` = keine Einschränkung, leere Liste = keine Treffer (FR-AUTH-09) |

### Interne Endpunkte (`InternalController`)

Feste Pfade ohne Präfix, gedacht für den Worker-Betrieb und das private Netz:

| Pfad | Aktion |
|---|---|
| `GET /health`, `GET /health/live` | Readiness (wie `/api/health`) und Liveness (`{"status":"ok"}`) |
| `POST /internal/ingest` | `IngestScheduler.trigger()` |
| `POST /internal/ingest/delete` | `{"database_name"}` löschen |
| `POST /internal/semantic/compile` | Kompilierung triggern |
| `POST /internal/semantic/reset` | `{"database_name"?}`, asynchron; ein unbekannter Name setzt nichts zurück (WARN im Log) |
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

## Code-Beispiele

### Ein neuer Endpunkt

```java
@RestController
@RequestMapping("${ao.api-prefix:/api}")            // nie "/api" hart kodieren (NFR-ARC-05)
public class ReviewController {
    private final SemanticRepository semantic;
    private final CurrentUser user;
    private final AoTime time;

    public ReviewController(SemanticRepository semantic, CurrentUser user, AoTime time) {
        this.semantic = semantic;
        this.user = user;
        this.time = time;
    }

    /** Liste mit Paging: negative Werte begrenzen, Form {"data", "count", "total"}. */
    @GetMapping("/reviews")
    public Map<String, Object> reviews(@RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> all = semantic.allTerms();
        List<Map<String, Object>> page = all.stream().skip(Math.max(0, skip)).limit(Math.max(0, limit)).toList();
        return Map.of("data", page, "count", page.size(), "total", all.size());
    }

    @PatchMapping("/reviews/{termId}")
    public Map<String, Object> review(@PathVariable String termId, @RequestBody Map<String, Object> body) {
        if (!Ids.isUuid(termId)) {
            throw ApiException.notFound("Term not found");               // ungültige ID: 404, kein 500
        }
        semantic.termById(termId).orElseThrow(() -> ApiException.notFound("Term not found"));
        String note = AppController.str(body.get("note"));               // "" und null → null, nie "null"
        if (note == null) {
            throw ApiException.unprocessable("note is required");        // vor dem Schreiben prüfen
        }
        Map<String, Object> values = new HashMap<>();                    // nur feste Schlüssel: patch baut SQL daraus
        values.put("description", note);
        values.put("modified", Timestamp.from(time.now()));              // eine menschliche Änderung
        values.put("modified_by", user.id());
        if (semantic.patchTerm(termId, values) != 1) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The change could not be stored");
        }
        return Map.of("data", semantic.termById(termId).orElseThrow());
    }
}
```

Der Controller wird in `ApiAutoConfiguration` per `@Import` registriert; ein Pfad, den es im
Original nicht gibt, kommt in die Allowlist von `OpenApiContractTest`.

### Die API von außen

```bash
# Chat mit Server-Sent Events; die conversation_id (UUID) vergibt der Client
curl -N -X POST localhost:8080/api/chat/completions -H 'Content-Type: application/json' \
     -d '{"question":"Top 5 customers by revenue in 2025","conversation_id":"6f1c2c0e-8a2b-4d6e-9f0a-3b1c5d7e9a11"}'
# data: {"type":"step","node":"question_intent","label":"Classifying the question","phase":"start"}
# data: {"type":"sql","node":"validate_sql_query","sql":"SELECT …"}
# data: {"type":"result","answer":{"response":"…","sql_code":"…","sql_response_from_db":[…]}}
# data: {"type":"charts","content":[{"viz_type":"bar","params":{…},"mode":"sql"}],"sql_response":[…]}
# data: [DONE]

curl -N 'localhost:8080/api/chat/watch?conversation_id=6f1c2c0e-…'       # an den laufenden Lauf anhängen (Replay ab Start)
curl -X POST localhost:8080/api/chat/cancel -H 'Content-Type: application/json' -d '{"conversation_id":"6f1c2c0e-…"}'

# zweite Completion, während der erste Client noch verbunden ist:
# HTTP 409 {"detail":"Conversation in progress"}
```

```bash
# Verbindung testen, anlegen (löst sofort einen Ingest aus) und wieder löschen
curl -X POST localhost:8080/api/connections/test -H 'Content-Type: application/json' \
     -d '{"connection":{"url":"jdbc:postgresql://db:5432/sales","username":"ro","password":"…"}}'
# {"success":true,"schemas":["public"]}
curl -X POST localhost:8080/api/connections -H 'Content-Type: application/json' \
     -d '{"connection":{"database_name":"sales","url":"jdbc:postgresql://db:5432/sales","username":"ro","password":"…","schemas":["public"]}}'
curl -X DELETE localhost:8080/api/connections/sales

# Fehlerformen
curl -X PATCH localhost:8080/api/terms/<id> -H 'Content-Type: application/json' -d '{"name":""}'
# HTTP 422 {"detail":"name must not be empty"}
curl -X POST 'localhost:8080/api/semantic-compilation/reset?database_name=slaes'
# HTTP 404 {"detail":"Database slaes not found"}
curl -X PUT localhost:8080/api/custom-analyses/<id> -H 'Content-Type: application/json' -d '{"sql":"DELETE FROM t"}'
# HTTP 422 {"detail":"only SELECT/WITH statements are allowed"}
```

### SSE aus einem eigenen Service senden

```java
SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
Thread.ofVirtual().name("my-stream").start(() -> {
    try {
        service.run(event -> send(emitter, Json.write(event)));
    } catch (RuntimeException e) {
        send(emitter, Json.write(Map.of("type", "error", "message", String.valueOf(e.getMessage()))));
    } finally {
        send(emitter, "[DONE]");            // immer senden: die Clients lesen bis zu dieser Zeile
        emitter.complete();
    }
});
return emitter;

private static void send(SseEmitter emitter, String payload) {
    try {
        // TEXT_PLAIN und führendes Leerzeichen ergeben exakt "data: <payload>"
        emitter.send(SseEmitter.event().data(" " + payload, MediaType.TEXT_PLAIN));
    } catch (IOException | IllegalStateException e) {
        // Client ist weg; der Lauf darf trotzdem zu Ende gehen
    }
}
```

Die Methode braucht `produces = MediaType.TEXT_EVENT_STREAM_VALUE` und sollte die Header
`Cache-Control: no-cache, no-transform` und `X-Accel-Buffering: no` setzen (Proxys puffern sonst).

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
- `emit` sendet unter dem Monitor des Runs. Ein sehr langsamer Client bremst damit den Agenten-Thread
  (wie zuvor auch); abgebrochene Verbindungen werden beim nächsten Sendeversuch oder Ping entfernt.
- **Re-Embedding nach dem Speichern:** `PATCH /terms`, `PATCH /nodes` und die SQL-Attribute-Routen
  schreiben erst in den Store und betten dann neu ein. Scheitert das Embedding, antwortet die Route
  mit 500, obwohl die Änderung gespeichert ist; ein erneuter Aufruf holt das Embedding nach.
- `ChatService` hält laufende Runs nur im Speicher; ein Neustart beendet sie (gespeichert ist dann
  nur die User-Nachricht).

## Anforderungsbezug

FR-API-01 bis FR-API-04, FR-CHAT-01 bis FR-CHAT-05, FR-CON-06, FR-ING-04, FR-SEM-07/08,
FR-GOV-01 bis FR-GOV-12, FR-AUTH-07 bis -09, NFR-ARC-03/05, NFR-SEC-03, NFR-OBS-02, NFR-TST-03/07.
