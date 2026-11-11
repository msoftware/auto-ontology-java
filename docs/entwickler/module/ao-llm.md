# Modul `ao-llm`

Paket `de.jentsch.autoontology.llm` · [Zurück zur Übersicht](../README.md)

## Zweck

Alles, was mit Modellen spricht: die LLM-Fassade `LlmGateway` mit strukturierter Ausgabe,
Embedding-Clients, der Rerank-Client, das Laden von Prompts und der Konfigurationsbericht beim
Start. Kein anderes Modul ruft LangChain4j oder einen Modell-Endpunkt direkt auf (NFR-ARC-04).

## Abhängigkeiten

`api`: `ao-common`, `dev.langchain4j:langchain4j` 1.23.0.
`implementation`: `langchain4j-open-ai` 1.23.0, `langchain4j-jackson3` 1.23.0-beta33.
An **jeder** LangChain4j-Abhängigkeit sind `com.fasterxml.jackson.core:jackson-databind` und
`jackson-core` ausgeschlossen (`noJackson2()` in `build.gradle.kts`). `langchain4j-jackson3` schaltet
LangChain4j per ServiceLoader auf Jackson 3. LangChain4j-Spring-Boot-Starter werden nicht verwendet.

## Klassen

| Klasse | Aufgabe |
|---|---|
| `LlmGateway` | Fassade: `structured(role, messages, type)`, `tryStructured(...)`, `text(...)`, `configured()` |
| `LangChain4jLlmGateway` | Implementierung auf `OpenAiChatModel` mit vier Structured-Output-Modi, Retry, Fallback, Nebenläufigkeitsgrenze |
| `ScriptedLlmGateway` | deterministische Implementierung für Tests: Antworten je Ausgabetyp, protokolliert jeden Aufruf |
| `ModelRole` | `REASONING`, `NON_REASONING`, `EMBED`, `RERANK` |
| `ModelSettings` | aufgelöstes Tripel (Endpunkt, Key, Modell) plus Modus je Rolle mit Fallback auf `ao.models.defaults` |
| `LlmMessage` | Rolle (`SYSTEM`, `USER`, `ASSISTANT`) und Inhalt |
| `LlmField` | Annotation für Feldbeschreibungen in Ausgabe-Records (`value`, `nullable`) |
| `LlmOutput` | optionaler Validierungshook `validate()` eines Ausgabe-Records |
| `OutputSchema` | JSON-Schema aus einem Record (als Map oder als LangChain4j-`JsonObjectSchema`) |
| `StructuredParser` | strikter Parser: JSON aus Antwort extrahieren, bereinigen, in den Record lesen, validieren |
| `LlmException` | Fehler, wenn alle Versuche gescheitert sind oder keine Rolle konfiguriert ist |
| `LlmMetrics` | Zähler für Aufrufe, Fehler, Dauer mit optionalem `Listener` |
| `Embedder` | Interface mit `InputType.QUERY`/`PASSAGE`, `embedQuery`, `embedPassages` |
| `OpenAiCompatibleEmbedder` | eigener `RestClient` gegen `/embeddings` mit `input_type`, `truncate`, Chunking, Retry, Dimensionsprüfung |
| `HashEmbedder` | lokales, deterministisches Embedding (Wörter und Zeichen-Trigramme, FNV-Hash, L2-normalisiert) |
| `RerankClient` | Rerank über zwei Schemata, gewählt nach URL-Endung |
| `Prompts` | lädt `prompts/<name>.txt` vom Classpath, entfernt `#!`-Kopfzeilen, füllt `{{name}}` |
| `ConfigurationReport` | loggt nach der Bean-Erzeugung die wirksame Konfiguration (Keys gekürzt) |
| `LlmAutoConfiguration` | registriert alle Beans |

## Beans

| Bean | Typ | Bedingung |
|---|---|---|
| `aoLlmMetrics` | `LlmMetrics` | `@ConditionalOnMissingBean` |
| `aoLlmGateway` | `LangChain4jLlmGateway` | `@ConditionalOnMissingBean(LlmGateway)` |
| `aoEmbedder` | `HashEmbedder` bei `ao.embedding.provider=hash`, sonst `OpenAiCompatibleEmbedder` | `@ConditionalOnMissingBean` |
| `aoRerankClient` | `RerankClient` | `@ConditionalOnMissingBean` |
| `aoConfigurationReport` | `ConfigurationReport` | `ao.log-configuration` (Default `true`) |

## Implementierungsdetails

### Rollen und Auflösung

`ModelSettings.resolve(models, role)` nimmt je Feld den Wert der Rolle, sonst den von
`ao.models.defaults`; `structuredOutput` fällt zuletzt auf `json_schema`. Eine Rolle ist
**nutzbar**, wenn Endpunkt und Modell gesetzt sind. `LangChain4jLlmGateway` baut Chat-Modelle nur
für `REASONING` und `NON_REASONING` (Temperatur 0, `maxRetries(0)` – der Retry liegt im Gateway,
`strictJsonSchema(true)`). Fehlt eine Rolle, wird die andere verwendet (`effective(role)`). Ein
leerer API-Key wird als `"none"` übergeben, weil LangChain4j einen Wert verlangt.

### Ablauf eines strukturierten Aufrufs

```
structured(role, messages, type)
 └─ attempt(role)                         bis ao.llm.max-retries Versuche (Default 3)
     └─ call(model, mode, messages, type)
         ├─ json_schema            ResponseFormat JSON mit JsonSchema aus OutputSchema.langchain(type)
         ├─ json_object            JSON-Modus; Schema als zusätzliche User-Nachricht im Prompt
         ├─ tool_calling           ein Tool <TypeName> mit dem Schema als Parametern, ToolChoice.REQUIRED
         └─ tool_calling_no_choice wie oben, ohne tool_choice (Claude hinter Gateways)
         → StructuredParser.parse(raw, type)
     Fehler → Metrik, WARN-Log, Backoff 500 ms · 2^i (max. 8 s), nächster Versuch
 └─ scheitert NON_REASONING komplett und REASONING existiert → derselbe Ablauf mit REASONING
 └─ sonst LlmException
```

Jeder Modellaufruf läuft innerhalb von `withPermit`: ein prozessweites `Semaphore` mit
`ao.llm.max-inflight` (Default 6) Plätzen. Der Timeout je Aufruf ist `ao.llm.invoke-timeout-s`
(Default 120 s) und wird LangChain4j übergeben.

`tryStructured` fängt `LlmException` und liefert `Optional.empty()`. Fast alle Aufrufer im Agenten
nutzen `tryStructured` und haben einen Fallback-Pfad.

### Strikte Ausgabe

`OutputSchema` erzeugt für einen Record: `type: object`, alle Komponenten in Deklarationsreihenfolge
als `properties`, alle `required`, `additionalProperties: false`. Unterstützt sind `String`,
Zahlen, `boolean`, Enums, `List<…>` und verschachtelte Records. `@LlmField(nullable = true)` macht
den Typ zu `["type", "null"]`.

`StructuredParser.parse`:

1. JSON-Objekt aus der Antwort ziehen (Code-Fences und Text davor/danach werden toleriert).
2. Schlüssel mit führendem `$` entfernen (geleakte Tool-Metadaten).
3. Ein leerer String für ein Listenfeld wird zu `[]` (rekursiv in verschachtelten Records).
4. Mit `FAIL_ON_UNKNOWN_PROPERTIES` und case-insensitiven Enums in den Record lesen.
5. Implementiert der Record `LlmOutput`, `validate()` aufrufen; eine `IllegalArgumentException`
   zählt als Validierungsfehler und löst einen neuen Versuch aus.

### Embeddings

- `OpenAiCompatibleEmbedder` sendet je Chunk (`ao.embedding.batch-size`, Default 32)
  `{model, input, input_type: query|passage, truncate: END}`. `input_type` lässt sich mit
  `ao.embedding.send-input-type=false` abschalten. Für Modelle aus
  `ao.embedding.text-prefixed-models` wird stattdessen `query: ` bzw. `passage: ` vorangestellt.
- Liefert der Endpunkt eine andere Dimension als `ao.vector.dimensions`, bricht der Aufruf mit
  einer Meldung ab, die den richtigen Wert nennt.
- `HashEmbedder` zerlegt camelCase und snake_case in Wörter, stemmt einfache Plurale, gewichtet
  Wörter (2.0) stärker als Trigramme (0.5) und normalisiert. Ähnliche Formulierungen ergeben
  ähnliche Vektoren; semantische Synonyme erkennt er nicht.

### Rerank

`RerankClient.configured()` ist wahr, wenn für `RERANK` Endpunkt und Modell gesetzt sind.
`scores(query, documents)` wählt das Schema nach der URL:

| URL endet auf | Request | Score aus |
|---|---|---|
| `…/rerank` | `{model, query, documents, top_n}` | `results[].relevance_score` |
| `…/reranking`, `…/ranking` | `{model, query: {text}, passages: [{text}], truncate: END}` | `rankings[].logit` |

### Prompts

`Prompts.get("sql_user_prompt")` lädt `prompts/sql_user_prompt.txt` vom Classpath (gecacht).
Zeilen, die am Dateianfang mit `#!` beginnen, sind Herkunftskommentare und werden entfernt; der
abschließende Zeilenumbruch gehört nicht zum Prompt. `Prompts.render(name, values)` ersetzt
`{{key}}` durch `String.valueOf(value)` (`null` → leer). Fehlende Platzhalter bleiben stehen.

Die Prompt-Dateien selbst liegen in den Modulen, die sie verwenden (`ao-agent`, `ao-semantic`).

### Konfigurationsbericht

`ConfigurationReport` implementiert `SmartInitializingSingleton` und loggt einmal nach dem Erzeugen
aller Singletons, vor dem Start der Scheduler: aktive Profile, Store-URL, je Rolle Endpunkt, Modell,
Modus und Key (die ersten 10 Zeichen, höchstens die Hälfte, plus Länge), Embedding-Provider,
Vektorstore, Verbindungen (Passwörter maskiert), Agent- und NL-Chart-Einstellungen.

## Erweiterungspunkte

- **Anderer LLM-Anbieter:** eigene `LlmGateway`-Bean. Sie muss Records gemäß `OutputSchema`
  liefern; `StructuredParser` kann wiederverwendet werden.
- **Neues Ausgabeschema:** Record mit `@LlmField`-Beschreibungen anlegen (Feldnamen wie im
  Original); bei Bedarf `LlmOutput.validate()` implementieren.
- **Anderes Embedding:** eigene `Embedder`-Bean; die Dimension muss zu `ao.vector.dimensions`
  passen.
- **Metriken:** `LlmMetrics.setListener(...)` mit einem Listener, der in eine Micrometer-Registry
  schreibt (derzeit nicht verdrahtet, siehe Fallstricke).

### Tests mit `ScriptedLlmGateway`

```java
new ScriptedLlmGateway()
    .on(SQLGenerationModel.class, prompt -> new SQLGenerationModel("thought", "SELECT 1", "answer"))
    .on(IntentValidationModel.class, prompt -> "{\"reasoning\":\"ok\",\"is_valid\":true, ...}")
    .onText(prompt -> "plain text");
```

Die Funktion bekommt alle Nachrichten verbunden als einen String und kann danach unterscheiden.
Rückgabe: eine Instanz des Typs, ein JSON-String (wird strikt geparst) oder `null` (→
`LlmException`, also der Fallback-Pfad des Aufrufers). `calls()` liefert die Liste der
angefragten Typnamen.

## Tests

- `LangChain4jLlmGatewayTest` – eingebetteter `com.sun.net.httpserver.HttpServer` als
  OpenAI-Stub; parametrisiert über alle vier Modi; unbekannte Felder und Validierungsfehler führen
  zu `LlmException`; prüft, dass Jackson-2-Databind nicht auf dem Classpath liegt.
- `ConfigurationReportTest` – Maskierung von Keys und Passwörtern.

## Fallstricke

- **`LlmMetrics` ist nicht an Micrometer angebunden.** Der Javadoc sagt, das API-Modul speise eine
  Registry; `setListener` wird aber nirgends aufgerufen. Die Zähler existieren nur im Speicher.
- Ein laufender HTTP-Aufruf wird durch `CancellationToken` **nicht** abgebrochen; er endet erst mit
  Antwort oder Timeout. Abgebrochen wird nur das Warten auf einen Platz bzw. der Backoff (per
  Interrupt).
- Die Retry-Schleife wiederholt **jeden** `RuntimeException`-Fehler, auch HTTP 4xx (z. B. falscher
  Modellname). Bei Konfigurationsfehlern dauert es daher `max-retries` × Backoff, bis die
  Exception kommt.
- `json_schema` verlangt einen Server mit Unterstützung für `response_format.json_schema`
  (strict). Lokale Server ohne diese Unterstützung mit `json_object` betreiben.
- Prompts werden prozessweit gecacht; Änderungen an Prompt-Dateien wirken erst nach Neustart.

## Anforderungsbezug

NFR-ARC-04, FR-LLM-01 bis FR-LLM-06, NFR-QD-01 (Jackson 3, keine Starter), FR-DEMO-06
(Hash-Embedding, Skript-Gateway), NFR-TST-01, NFR-SEC-02.
