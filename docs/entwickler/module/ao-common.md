# Modul `ao-common`

Paket `de.jentsch.autoontology.common` · [Zurück zur Übersicht](../README.md)

## Zweck

Basis aller anderen Module: der Konfigurationsbaum `ao.*`, der feste Benutzer, die gemeinsame Uhr,
Fehlerformate der API, die Prüfung der API-Präfixe, kooperativer Abbruch und kleine Hilfsklassen für
IDs, JSON und Ergebniszeilen. Das Modul enthält **keine** Fachlogik.

## Abhängigkeiten

`api`: `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, Jackson 3 (`tools.jackson.core:jackson-databind`).
Annotation-Processor: `spring-boot-configuration-processor` (erzeugt Metadaten für die IDE-Autovervollständigung von `ao.*`).

Alle anderen Module hängen (transitiv) von `ao-common` ab.

## Klassen

| Klasse | Aufgabe |
|---|---|
| `AoProperties` | `@ConfigurationProperties("ao")` als verschachtelter Record mit `@DefaultValue`s. Einzige Quelle für alle `ao.*`-Werte |
| `CommonAutoConfiguration` | aktiviert `AoProperties`, lädt `ao-defaults.properties`, prüft die Präfixe, registriert `CurrentUser`, `AoTime`, `ApiExceptionHandler` |
| `CurrentUser` | Interface mit `id()` und `displayName()`; `CurrentUser.fixed(...)` ist die einzige Implementierung |
| `AoClock` | baut eine `Clock` aus `ao.clock.fixed-now` (ISO-Datum → 00:00 UTC, sonst ISO-Instant) |
| `AoTime` | Record um die gemeinsame `Clock` mit `now()` und `today()` (UTC) |
| `ApiException` | Laufzeitfehler mit `HttpStatus`, `Style` (`DETAIL`/`ERROR`/`PLAIN`) und Payload; Fabrikmethoden `notFound`, `badRequest`, `conflict`, `unprocessable`, `badGateway`, `error`, `plain` |
| `ApiExceptionHandler` | `@RestControllerAdvice(basePackages = "de.jentsch.autoontology")`: rendert `ApiException` je Stil und Bean-Validation-/Parse-Fehler als FastAPI-artige 422 |
| `ApiPrefixValidator` | Startprüfung von `ao.api-prefix` und `ao.nl-chart.api-prefix` |
| `CancellationToken` | Abbruch-Flag mit Hooks und Thread-Bindung |
| `Ids` | `newId()` (UUID-String), `isUuid(value)` |
| `Json` | statischer, toleranter `JsonMapper` (unbekannte Felder werden ignoriert) für JSON in Textspalten |
| `Rows` | normalisiert JDBC-Zeilen (Schlüssel klein, `Clob` → String, `Timestamp` → ISO-Instant); Lesehilfen `str`, `strOr`, `bool` |

## Beans

| Bean | Typ | Bedingung |
|---|---|---|
| `aoCurrentUser` | `CurrentUser` | `@ConditionalOnMissingBean` |
| `aoTime` | `AoTime` | `@ConditionalOnMissingBean` (eine eigene `AoTime`-Bean des Hosts ersetzt sie) |
| (Import) | `ApiExceptionHandler` | immer |

## Implementierungsdetails

### Konfiguration und Defaults

`AoProperties` ist unveränderlich. Untergruppen (Auszug): `user`, `clock`, `vector` (+`qdrant`),
`models` (`defaults`, `reasoning`, `nonReasoning`, `embed`, `rerank`), `embedding`, `llm`, `agent`,
`semantic`, `ingest` (+`connections[]`), `security`, `nlChart`, `mcp`, `dashboard`, `demo`.
Die vollständige Liste mit Defaults steht in [`../../DOKUMENTATION.md`](../../DOKUMENTATION.md),
Abschnitt 10.

`ao-defaults.properties` wird per `@PropertySource` geladen und hat damit die **niedrigste**
Priorität. Es mappt die Umgebungsvariablen des Originals (`CONNECTION_STRINGS`,
`DEFAULT_MODELS_*`, `REASONING_*`, `LLM_MAX_INFLIGHT`, `DB_PROBE_*` …) auf `ao.*`. Setzt ein Host
`ao.models.defaults.endpoint` selbst, gewinnt sein Wert.

`resolvedNlChartPrefix()` liefert `ao.nl-chart.api-prefix` oder, wenn leer,
`${ao.api-prefix}/nl-chart`.

Einige Schalter werden nicht über `AoProperties`, sondern direkt per `@Value`/`@ConditionalOnProperty`
gelesen: `ao.internal.secret`, `ao.mcp.enabled`, `ao.log-configuration`,
`ao.store.liquibase.enabled`.

Die `toString()`-Methoden von `Qdrant` und `Model` blenden API-Keys aus (NFR-SEC-02).

### Präfixprüfung

`CommonAutoConfiguration` ruft im Konstruktor `ApiPrefixValidator.validate(...)` für beide Präfixe
auf, mit dem Wert von `quasar-dashboard.api-prefix`, falls vorhanden. Abgelehnt werden: leer, ohne
führenden `/`, mit abschließendem `/`, gleich oder unterhalb von `/internal`, `/mcp`, `/actuator`,
`/h2-console` sowie gleich oder unterhalb des Dashboard-Präfixes. Die Ausnahme
(`IllegalStateException`) bricht den Kontextstart mit klarer Meldung ab.

### Uhr

`aoTime` nimmt `ao.clock.fixed-now`, wenn gesetzt; sonst die **erste** `Clock`-Bean im Kontext
(in der Dashboard-Demo `quasarDashboardClock`); sonst `Clock.systemUTC()`. `AoTime` ist bewusst ein
Wrapper statt einer eigenen `Clock`-Bean, damit Hosts eigene `Clock`-Beans behalten können, ohne
dass Injektionen mehrdeutig werden.

### Fehlerformate

| Stil | Body | Verwendet für |
|---|---|---|
| `DETAIL` (Default) | `{"detail": "<msg>"}` | Katalog, Terms, SQL Attributes, Governance, Exploration, Import/Export, Chat, NL-Charts |
| `ERROR` | `{"error": "<msg>"}` | Konversationen, Akronyme, Custom Prompts, Konfiguration, Analytics |
| `PLAIN` | Klartext | z. B. `Conversation not found` |

Bean-Validation, unlesbare Bodies, fehlende Parameter und Typfehler werden zu
`422 {"detail": [{"loc": [...], "msg": ..., "type": "value_error"}]}`. 5xx-Antworten werden als
WARN geloggt. Eine `ApiException` ohne Meldung (`detail == null`) wird mit leerem Text gerendert –
sie darf den Fehler nicht selbst in einen 500 verwandeln.

### Abbruch (`CancellationToken`)

```java
CancellationToken token = new CancellationToken();
try (CancellationToken.Scope s = token.bind()) {          // an den aktuellen Thread binden
    ...                                                   // Konnektoren finden es über current()
    token.check();                                        // wirft CancelledException, wenn abgebrochen
}
token.cancel();                                           // aus einem anderen Thread: Flag + alle Hooks
```

- `onCancel(hook)` registriert einen Hook und liefert einen `Scope` zum Abmelden. Ist das Token
  schon abgebrochen, läuft der Hook sofort.
- `cancel()` ist idempotent; Fehler in Hooks werden verschluckt („best effort“).
- `bind()` stellt beim Schließen das vorherige Token wieder her (verschachtelbar).

### JSON und Zeilen

- `Json.MAPPER` ist tolerant (`FAIL_ON_UNKNOWN_PROPERTIES` aus) und für **gespeicherte** Daten
  gedacht. Für LLM-Ausgaben gibt es den strikten Parser in `ao-llm`.
- `Json.readAny` liefert bei ungültigem JSON den Rohtext zurück, `readStringList` eine Liste mit
  dem Rohtext, `readList` eine leere Liste – je nachdem, was der Aufrufer bei Altdaten erwartet.
- `Rows.bool` akzeptiert `Boolean` und Strings wie `"true"` (für Stores, die Booleans anders
  liefern).
- `Rows.strOr(row, key, def)` liefert den Text eines Feldes oder den Default. **Nicht**
  `String.valueOf(row.getOrDefault(key, ""))` schreiben: `getOrDefault` gibt bei vorhandenem
  Schlüssel mit Wert `null` eben `null` zurück, und daraus wird der Text `"null"` (so landeten leere
  Beschreibungen als „null“ in API-Antworten, Exporten und Prompts).

## Erweiterungspunkte

- **Anmeldung nachrüsten:** eine eigene `CurrentUser`-Bean (z. B. aus dem `SecurityContext`)
  ersetzt den festen Benutzer überall.
- **Neue Einstellung:** Komponente im passenden Record von `AoProperties` mit `@DefaultValue`
  ergänzen; bei einer Umgebungsvariable des Originals zusätzlich in `ao-defaults.properties`
  mappen.
- **Eigene Uhr:** `ao.clock.fixed-now`, eine `Clock`-Bean im Host oder – für volle Kontrolle – eine
  eigene `AoTime`-Bean.

## Code-Beispiele

### Eine neue Einstellung `ao.*`

```java
// AoProperties.java – Komponente im passenden Record ergänzen, immer mit @DefaultValue
public record Agent(
        @DefaultValue("false") boolean validateSqlValues,
        // ...
        @DefaultValue("1000") int maxResultRows,
        @DefaultValue("3") int maxJoinDepth) {}          // neu: ao.agent.max-join-depth
```

```properties
# ao-defaults.properties – nur wenn es eine Umgebungsvariable des Originals dafür gibt
ao.agent.max-join-depth=${MAX_JOIN_DEPTH:3}
```

```java
// Verwendung: AoProperties wird überall per Konstruktor gereicht
int depth = props.agent().maxJoinDepth();
```

Relaxed Binding macht daraus automatisch `AO_AGENT_MAX_JOIN_DEPTH`. Tests bauen den Record ohne
Spring-Kontext:

```java
AoProperties props = new Binder(new MapConfigurationPropertySource(
        Map.of("ao.vector.dimensions", "64", "ao.agent.max-join-depth", "2")))
        .bindOrCreate("ao", AoProperties.class);
```

### Fehler aus einem Controller melden

```java
@GetMapping("/terms/{id}")
public Map<String, Object> term(@PathVariable String id) {
    if (!Ids.isUuid(id)) {                                  // ungültige IDs sind 404, nie 500 (FR-DB-02)
        throw ApiException.notFound("Term not found");      // → 404 {"detail": "Term not found"}
    }
    return semantic.termById(id).orElseThrow(() -> ApiException.notFound("Term not found"));
}

throw ApiException.unprocessable("name must not be empty");            // 422 {"detail": ...}
throw ApiException.conflict("A term with this name already exists");   // 409 {"detail": ...}
throw ApiException.error(HttpStatus.BAD_REQUEST, "title is required"); // 400 {"error": ...}  (Next.js-Routen)
throw ApiException.plain(HttpStatus.NOT_FOUND, "Conversation not found"); // 404, text/plain
throw ApiException.badGateway("The language model did not answer");    // 502: der Fehler liegt beim Modell
```

Faustregel: Alles, was der Aufrufer durch eine andere Eingabe beheben kann, ist 4xx und wird **vor**
dem Schreiben geprüft. Eine verletzte `NOT NULL`- oder `UNIQUE`-Bedingung, die erst die Datenbank
bemerkt, kommt als 500 beim Client an.

### Einen abbrechbaren Lauf bauen

```java
CancellationToken token = new CancellationToken();
Thread.ofVirtual().start(() -> {
    try (CancellationToken.Scope scope = token.bind()) {    // ab hier sehen Konnektoren das Token
        for (String table : tables) {
            token.check();                                  // zwischen den Schritten: wirft CancelledException
            connector.execute("SELECT COUNT(*) FROM " + table, List.of(), 30, 1);   // laufendes Statement
        }                                                   // wird bei cancel() per Statement.cancel() beendet
    } catch (CancellationToken.CancelledException e) {
        log.info("cancelled");
    }
});
token.cancel();                                             // z. B. aus dem HTTP-Thread von /chat/cancel
```

Eigene Ressourcen hängt man mit `onCancel` ein:

```java
try (CancellationToken.Scope hook = token.onCancel(httpCall::abort)) {
    httpCall.execute();
}                                                           // Scope schließt → Hook abgemeldet
```

### Zeilen und JSON lesen

```java
Map<String, Object> row = db.one("SELECT * FROM term WHERE id = :id", Map.of("id", id)).orElseThrow();
String name = Rows.str(row, "name");                        // null, wenn die Spalte NULL ist
String description = Rows.strOr(row, "description", "");    // nie der Text "null"
boolean certified = Rows.bool(row, "name_certified");
List<String> synonyms = Json.readStringList((String) row.get("synonyms"));   // JSON in einer Textspalte
String json = Json.write(Map.of("viz_type", "bar"));        // null → null
```

## Tests

Keine eigenen Tests im Modul. Abgedeckt über `CustomApiPrefixTest` (`ao-app`: Präfixprüfung, 404
unter `/api` bei anderem Präfix) und indirekt über alle Integrationstests.

## Fallstricke

- **`ao-defaults.properties` enthält echte Fallback-Werte** für `DEFAULT_MODELS_ENDPOINT`
  (`http://localhost:8888/v1`), ein Modell und einen API-Key. Fehlen die Umgebungsvariablen, wird
  dieser Endpunkt benutzt. Für Betrieb und Weitergabe die Defaults leeren und Werte über die
  Umgebung setzen; den Key austauschen.
- `AoClock.from` unterscheidet Datum und Instant über die Länge (≤ 10 Zeichen = Datum).
- `ApiExceptionHandler` greift nur für Controller in `de.jentsch.autoontology.*`. Ein Controller in
  einem Host-Paket bekommt die Fehlerbehandlung des Hosts.

## Anforderungsbezug

FR-CFG-01, FR-AUTH-01, NFR-QD-05, FR-DEMO-02 (`fixed-now`), FR-API-02, NFR-ARC-05, NFR-QD-04,
FR-SEM-06/FR-CHAT-02 (Abbruch), FR-DB-02 (UUID-IDs), NFR-SEC-02.
