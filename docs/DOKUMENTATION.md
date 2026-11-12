# Auto-Ontology (Java/Quasar) – Projektdokumentation

Stand: 2026-11-11, Version `0.1.0-SNAPSHOT`. Grundlage: Anforderungsdokument
`../ANFORDERUNGEN_auto-ontology-java.md` (Version 1.10) und der Code in diesem Verzeichnis.
Die Abweichungen der Umsetzung stehen gesammelt in [`ABWEICHUNGEN.md`](ABWEICHUNGEN.md); diese
Dokumentation verweist darauf, statt sie zu wiederholen. Architektur- und Implementierungsdetails
je Backend-Modul stehen im [Entwicklerhandbuch](entwickler/README.md), die des Frontends im
[Frontend-Entwicklerhandbuch](entwickler/frontend/README.md).

> **Sicherheitshinweis.** Auto-Ontology ist ein internes Werkzeug **ohne Anmeldung und ohne
> Rechteprüfung**. Wer die Anwendung erreicht, darf alles: Verbindungen anlegen und löschen, die
> semantische Schicht zurücksetzen, Modelle importieren. Die Anwendung darf nie aus dem Internet
> erreichbar sein (NFR-SEC-06). Ohne Container bindet sie an `127.0.0.1`, die Compose-Dateien
> veröffentlichen Ports nur auf `127.0.0.1`.

---

## Inhalt

1. [Was das System tut](#1-was-das-system-tut)
2. [Architektur](#2-architektur)
3. [Technologie-Stack](#3-technologie-stack)
4. [Die Pipeline im Detail](#4-die-pipeline-im-detail)
5. [Datenmodell](#5-datenmodell)
6. [REST-API](#6-rest-api)
7. [MCP-Server](#7-mcp-server)
8. [Charts aus natürlicher Sprache (quasar-dashboard)](#8-charts-aus-natürlicher-sprache-quasar-dashboard)
9. [Frontend](#9-frontend)
10. [Konfiguration](#10-konfiguration)
11. [Betrieb](#11-betrieb)
12. [Build und Tests](#12-build-und-tests)
13. [Erweitern und Einbetten](#13-erweitern-und-einbetten)
14. [Umsetzungsstand gegenüber den Anforderungen](#14-umsetzungsstand-gegenüber-den-anforderungen)
15. [Lizenz und Herkunft](#15-lizenz-und-herkunft)
16. [Glossar](#16-glossar)

---

## 1. Was das System tut

Auto-Ontology baut aus den Metadaten relationaler Datenbanken eine **semantische Schicht
(Ontologie)** und nutzt sie, um Fragen in natürlicher Sprache in **geprüftes SQL** zu übersetzen.
Das SQL wird ausgeführt; die Antwort enthält Text, SQL, Ergebniszeilen und die Lösungsschritte des
Agenten.

Das Projekt ist ein Nachbau von **NVIDIA Auto-Ontology** (Python/FastAPI/Next.js, Apache 2.0) auf
**Java 21 / Spring Boot 4.1** und **Vue 3 / Quasar 2**. Es soll später in
`../quasar-dashboard` eingebettet werden, damit Anwender dort Dashboard-Charts per Frage erzeugen
können (Anforderungen, Abschnitt 23).

Der Ablauf hat drei Phasen:

| Phase | Was passiert | Wo im Code |
|---|---|---|
| **Generate** | Katalog einlesen (Schemas, Tabellen, Spalten, Schlüssel, Query-Historie), Spalten profilieren, per LLM **Terms** (Geschäftsbegriffe) mit **Column Attributes** ableiten, **Semantic FKs** auflösen, **SQL Attributes** vorschlagen, alles in den Vektorstore schreiben | `ao-ingestion`, `ao-semantic` |
| **Certify** | Menschen prüfen, bearbeiten und zertifizieren Namen, Beschreibungen und SQL Attributes | `ao-api` (Terms, SQL Attributes, Katalog), Frontend-Seiten *Terms* und *Data* |
| **Answer** | Ein Agent-Graph klassifiziert die Frage, sucht Kandidaten per Vektorsuche, erzeugt SQL, prüft es statisch und per LLM, repariert bei Bedarf, führt es aus und formuliert die Antwort | `ao-agent`, `ao-api` (Chat) |

Dazu kommen Governance (Tags, Regeln, Zonen, Owner, Gruppen, PII-Erkennung), globale Suche,
Graph-Exploration, Import/Export als YAML, ein read-only MCP-Server für externe Agenten und das
NL-Chart-Modul für quasar-dashboard.

---

## 2. Architektur

### 2.1 Grundprinzip: modularer Monolith

Das Original verteilt sich auf vier Prozesse (Next.js-Gateway, FastAPI-Backend,
Ingestion-Dienst, MCP-Server). Der Nachbau ist ein **modularer Spring-Boot-Monolith**
(NFR-ARC-01): ein Prozess, aufgeteilt in Gradle-Module. Jedes Fachmodul bringt eine eigene
Auto-Konfiguration mit, sodass die Module als **Starter-Bibliothek** in fremde Hosts eingebaut
werden können (NFR-QD-03). Die eigenständige Anwendung `ao-app` ist nur einer von mehreren
möglichen Hosts.

```
                      ┌──────────────────────── Browser ────────────────────────┐
                      │  Quasar-App (apps/demo)                                  │
                      │   @jentsch/quasar-ontology   @jentsch/quasar-dashboard-nl│
                      └───────────────┬───────────────────────┬─────────────────┘
                                      │ /api/**  (SSE)        │ /api/nl-chart/**
┌─────────────────────────────────────▼───────────────────────▼──────────────────┐
│ Spring Boot Host (ao-app bzw. ao-dashboard-demo)                               │
│                                                                                │
│  ao-api ──────────── REST, SSE-Chat, OpenAPI        ao-nl-chart ─ NL-Charts    │
│    │                                                  │  (nutzt quasar-        │
│  ao-agent ────────── Text-to-SQL-Graph                │   dashboard-backend)   │
│    │                                                  │                        │
│  ao-semantic ─────── Kompilierung der Ontologie       ao-mcp ── /mcp (read-only│
│    │                                                         HTTP-Client der   │
│  ao-ingestion ────── Ingest, Scheduler, PII, Regeln          eigenen API)      │
│    │         │         │          │                                            │
│  ao-store  ao-vector  ao-llm  ao-connectors                                    │
│    │         │         │          │                                            │
│  ao-common (Properties, fester Benutzer, Uhr, Fehlerformate)                   │
└────┼─────────┼─────────┼──────────┼────────────────────────────────────────────┘
     ▼         ▼         ▼          ▼
 Metadaten-  Qdrant /   OpenAI-    Quell-DBs (PostgreSQL, Oracle,
 Store       In-Memory  kompatible SQL Server, H2, DataSource-Bean)
 (Postgres/H2)          Endpunkte
```

### 2.2 Module

Alle Module liegen unter `backend/`, Gruppe `de.jentsch.autoontology`, Paket
`de.jentsch.autoontology.<modul>`. Die Abhängigkeiten laufen strikt von oben nach unten.

| Modul | Aufgabe | Wichtige Klassen | Hängt ab von |
|---|---|---|---|
| `ao-common` | Gemeinsame Konfiguration (`ao.*`), fester Benutzer, Uhr, Fehlerformate, Präfixprüfung, Hilfsklassen | `AoProperties`, `CurrentUser`, `AoClock`/`AoTime`, `ApiException`, `ApiExceptionHandler`, `ApiPrefixValidator`, `CancellationToken` | Spring Web, Validation, Jackson 3 |
| `ao-store` | Metadaten-Store: Liquibase-Schema, Repositories auf `JdbcClient` (bewusst kein JPA) | `AoSpringLiquibase`, `CatalogRepository`, `SemanticRepository`, `GovernanceRepository`, `AppRepository`, `HistoryRepository`, `RuntimeSettings` | `ao-common`, JDBC, Liquibase |
| `ao-vector` | Vektorstore-Abstraktion | `VectorStore`, `QdrantVectorStore`, `InMemoryVectorStore` | `ao-store`, `io.qdrant:client` |
| `ao-llm` | LLM-Fassade, Embedding, Rerank, Prompts, Structured Output | `LlmGateway`, `LangChain4jLlmGateway`, `ScriptedLlmGateway`, `Embedder`, `OpenAiCompatibleEmbedder`, `HashEmbedder`, `RerankClient`, `OutputSchema`, `Prompts`, `ConfigurationReport` | `ao-common`, LangChain4j (Jackson-3-Modus) |
| `ao-connectors` | Quell-Datenbank-Konnektoren | `SourceDatabase`, `JdbcSourceDatabase`, `H2Source`, `PostgresSource`, `OracleSource`, `SqlServerSource`, `Connectors`, `ReadOnlySql`, `TableFilter`, `DbErrors` | `ao-common`, JDBC-Treiber, JSqlParser |
| `ao-ingestion` | Katalog-Ingest, Scheduler, Query-Historie, PII, Regeln, globale Suche, Verbindungsverwaltung | `IngestService`, `IngestScheduler`, `CatalogExtractor`, `CatalogWriter`, `QueryHistoryParser`, `DataLayerEmbedder`, `EmbeddingTexts`, `PiiDetection`, `RuleService`, `SearchService`, `ConnectionService` | Store, Vector, LLM, Connectors |
| `ao-semantic` | Semantische Kompilierung (Ontologie bauen) | `SemanticCompiler`, `SemanticCompilationService`, `Profiler`, `DateFormats`, `TableTaxonomy`, `SemanticEmbedder`, `DomainContext` | `ao-ingestion` |
| `ao-agent` | Text-to-SQL-Agent | `AgentGraph`, `AgentRunner`, `AgentState`, `Nodes`, `SqlStaticChecks`, `JoinPathFinder`, `EntityCoverageService`, `ChartAdvisor`, `AgentPrompts`, `NodeLabels` | `ao-semantic` |
| `ao-api` | REST-Controller, SSE-Chat, OpenAPI (springdoc), interne Endpunkte | `CatalogController`, `SemanticController`, `GovernanceController`, `ExplorationController`, `AppController`, `ModelInterchangeController`, `InternalController`, `chat.ChatController`, `chat.ChatService` | `ao-agent`, springdoc |
| `ao-mcp` | MCP-Server (Streamable HTTP) unter `/mcp` | `McpAutoConfiguration`, `McpTools` | `ao-common`, MCP Java SDK |
| `ao-nl-chart` | Charts aus natürlicher Sprache für quasar-dashboard | `NlChartService`, `NlChartController`, `DatasetSync`, `ChartValidator`, `QueryContextBuilder`, `SqlResults` | `ao-api`, `quasar-dashboard-backend` |
| `ao-spring-boot-starter` | Bündelt alle Module außer `ao-nl-chart` | – | `ao-api`, `ao-mcp` |
| `ao-app` | Eigenständiger Host; Profile `default`, `demo`, `qdrant`, `worker`; Demo-Datenbank „Sales“ | `AoApplication`, `demo.DemoConfiguration`, `demo.DemoDatabase` | Starter, Actuator |
| `ao-dashboard-demo` | Kombinierter Host: quasar-dashboard-Backend + Beispieldaten + Auto-Ontology + NL-Charts | `DashboardDemoApplication` | Starter, `ao-nl-chart`, quasar-dashboard Sample Data |

Jedes Fachmodul registriert seine Auto-Konfiguration über
`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
Fast alle Beans sind mit `@ConditionalOnMissingBean` deklariert und lassen sich im Host ersetzen
(Abschnitt 13).

### 2.3 Composite Build mit quasar-dashboard

`backend/settings.gradle.kts` bindet `../../quasar-dashboard/backend` als **Composite Build** ein.
`ao-nl-chart` und `ao-dashboard-demo` beziehen daraus `quasar-dashboard-backend` und
`quasar-dashboard-backend-sample-data`. Das Nachbarverzeichnis `C:\Projekte\Text2SQL\quasar-dashboard`
muss deshalb vorhanden sein, sonst bricht der Gradle-Build ab. Das Repository quasar-dashboard wird
von diesem Projekt **nicht** verändert.

Auch das Frontend nutzt quasar-dashboard direkt aus den Quellen
(`quasar.config.ts` setzt einen Vite-Alias auf `quasar-dashboard/packages/dashboard/src`).

---

## 3. Technologie-Stack

| Belang | Umsetzung | Version (Version Catalog `backend/gradle/libs.versions.toml`) |
|---|---|---|
| Sprache | Java 21, Virtual Threads aktiv (`spring.threads.virtual.enabled`) | Toolchain 21 |
| Framework | Spring Boot (Web MVC), SSE über `SseEmitter` | 4.1.1 |
| JSON | Jackson 3 (`tools.jackson.*`) | aus Boot-BOM |
| Persistenz | `JdbcClient`, Liquibase | aus Boot-BOM |
| LLM | LangChain4j (`langchain4j`, `langchain4j-open-ai`, `langchain4j-jackson3`), **ohne** Spring-Boot-Starter; Jackson 2 an jeder LangChain4j-Abhängigkeit ausgeschlossen | 1.23.0 / 1.23.0-beta33 |
| SQL-Parser | JSqlParser | 5.4 |
| Vektorstore | Qdrant-Java-Client (gRPC) | 1.20.0, Server-Image `qdrant/qdrant:v1.20.0` |
| MCP | MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`) | 2.1.0 |
| OpenAPI | springdoc-openapi (webmvc-api) | 3.2.0 |
| JDBC-Treiber | H2, PostgreSQL, Oracle (`ojdbc11`), SQL Server (`mssql-jdbc`) | aus Boot-BOM |
| Tests | JUnit 5, Spring Boot Test, eingebettetes PostgreSQL (zonky), Testcontainers (Qdrant, Oracle Free, SQL Server) | embedded-postgres 2.2.2, Binaries 18.6.0 |
| Build | Gradle Kotlin DSL, `-parameters -Xlint:all,-processing,-serial -Werror`, JaCoCo, Tests mit `user.timezone=UTC` | Gradle 9.8 |
| Frontend | Quasar 2 (`@quasar/app-vite`), Vue 3.5, TypeScript ~5.9 strict, Pinia, vue-i18n 11, ECharts 6 über `vue-echarts`, pnpm-Workspace, Vitest | siehe `frontend/**/package.json` |

---

## 4. Die Pipeline im Detail

### 4.1 Quell-Datenbanken und Verbindungen (`ao-connectors`, `ao-ingestion`)

**Konnektor-Vertrag.** `SourceDatabase` bildet `SQLDatabase` aus `connectors/base.py` des
Originals nach. Die Metadatenmethoden liefern Zeilen mit den Spaltennamen des Originals:

| Methode | Rückgabe |
|---|---|
| `getTables()` | `table_schema`, `table_name`, `table_type` (`base table` / `view` / `materialized view`) |
| `getColumns()` | `table_schema`, `table_name`, `column_name`, `data_type`, `is_nullable` (echter Boolean), `ordinal_position` |
| `getViews()`, `getPks()`, `getFks()` | wie im Original |
| `getQueries(hours)` | `end_time`, `query_text`; ohne Historie leer. Der Ingest ruft den Default von 24 h auf |
| `getColumnComments()` | neu: Tabellen- und Spaltenkommentare als Startbeschreibungen |
| `sampleQuery(schema, table, n)` | neu: dialektgerechte Stichprobe (Oracle und T-SQL kennen kein `LIMIT`) |
| `limit(sql, n)` | dialektgerechte Zeilenbegrenzung (`LIMIT`, `FETCH FIRST`, `TOP`) |
| `execute(sql, params, timeout, maxRows)` | Ausführung mit Timeout, Zeilenlimit und Abbruch über `Statement.cancel()` |
| `dialect()`, `qualify()`, `quote()`, `listSchemas()` | Dialekt (`postgres`, `oracle`, `tsql`, `h2`), Quoting, Schemaliste für den Verbindungstest |

**Implementierungen.** `JdbcSourceDatabase` liest die Metadaten über `java.sql.DatabaseMetaData`
(ein Codepfad für alle Engines, siehe `ABWEICHUNGEN.md`). Die Unterklassen ergänzen die
Besonderheiten:

| Klasse | Engine | Besonderheiten |
|---|---|---|
| `PostgresSource` | `jdbc:postgresql:` | Query-Historie aus `pg_stat_activity` |
| `OracleSource` | `jdbc:oracle:thin:` | `FETCH FIRST n ROWS ONLY`, Schema = Owner, Bezeichner in Großschrift |
| `SqlServerSource` | `jdbc:sqlserver:` | `TOP n`, `[name]`-Quoting, `MS_Description` aus `sys.extended_properties` |
| `H2Source` | `jdbc:h2:` | `LIMIT n`; kommt mit `DATABASE_TO_LOWER=TRUE` zurecht; liefert die Demo-Historie aus `QUERY_HISTORY`, falls vorhanden |

`Connectors` wählt die Klasse nach dem JDBC-URL-Schema bzw. bei einer `DataSource`-Bean nach dem
Produktnamen der Datenbank. `Connectors.wrap(name, dataSource)` erzeugt einen Konnektor auf einer
vorhandenen `DataSource`, ohne deren Pool zu übernehmen (FR-NLC-01). So erreicht Auto-Ontology
auch eine In-Memory-H2 im selben Prozess.

**Nur lesend.** `ReadOnlySql` lässt für alle vom Agenten ausgeführten Statements nur ein einzelnes
`SELECT`/`WITH` zu (FR-CON-05). Die Prüfung läuft rekursiv über das ganze Statement: Auch ein
schreibendes CTE (`WITH d AS (DELETE … RETURNING *) SELECT …`), `SELECT … INTO` und
`FOR UPDATE` in Unterabfragen werden abgelehnt. Der Timeout kommt aus der Laufzeiteinstellung
`sql_query_timeout_seconds`. Trotzdem gilt: Quell-Datenbanken immer mit einem Benutzer mit reinen
Leserechten anbinden – Funktionen mit Seiteneffekten erkennt keine Syntaxprüfung.

**Tabellenfilter.** `TableFilter` wendet zentral bei der Extraktion an: Schema-Allowlist,
`table_allow_regex` und `table_deny_regex` (Deny gewinnt, `find()` gegen den unqualifizierten
Namen, case-sensitiv).

**Verbindungsquellen** (`ConnectionService`):

1. in der UI bzw. per `POST /api/connections` angelegt; Credentials als JSON in
   `catalog_database.connection`, über die API nie zurückgegeben,
2. `CONNECTION_STRINGS` (komma- oder zeilengetrennt, gemappt auf `ao.ingest.connection-strings`),
3. `ao.ingest.connections[]` mit `url` **oder** `datasource-bean`, optional `schemas`,
   `table-allow-regex`, `table-deny-regex`.

Die Reihenfolge ist absichtlich asymmetrisch (FR-CON-04): Beim **Beantworten** gelten
UI-Verbindungen, wenn es welche gibt; bei der **Ingestion** gelten konfigurierte Verbindungen, wenn
gesetzt. Passwörter, Tokens und API-Keys werden in Logs maskiert.

### 4.2 Ingestion (`ao-ingestion`)

`IngestService.runIngest` (Vorbild `run_ingest`) läuft je Verbindung, serialisiert:

1. Konnektor erzeugen.
2. Katalog extrahieren (`CatalogExtractor`) und Tabellenfilter anwenden. Hat eine gelistete Relation
   keine Spalten, bricht die Extraktion ab (Vollständigkeitsprüfung).
3. Gegen den Bestand diffen und schreiben (`CatalogWriter`): neue, geänderte, entfernte Objekte;
   deklarierte FKs über `last_seen` aufräumen.
4. Query-Historie mit JSqlParser parsen (`QueryHistoryParser`): referenzierte Tabellen und Spalten,
   beobachtete Joins und Unions, Deduplizierung über einen Hash des SQL-Texts, Zähler fortschreiben.
5. Wenn `pii_detection_enabled`: PII-Erkennung (`PiiDetection`, nur Regeln).
6. Embedding-Texte für Tabellen und Spalten bauen (`EmbeddingTexts`, Format exakt nach
   `utils/embedding_rows.py` – das ist Teil des Retrieval-Vertrags).
7. In Chunks einbetten und in die Collection `data_objects_layer` schreiben (`DataLayerEmbedder`).
   Der alte Bestand der Datenbank wird **erst vor dem ersten erfolgreich eingebetteten Chunk**
   gelöscht; scheitert das Embedding vorher, bleibt er erhalten.
8. Ist die Kompilierung abgeschaltet: Regeln erneut anwenden (`RuleService`).

Fehler in den Stufen PII und Regeln werden geloggt, markieren den Ingest aber nicht als
fehlgeschlagen (NFR-REL-01).

**Scheduler** (`IngestScheduler`): läuft beim Start und danach alle `INGEST_INTERVAL_HOURS`
(Default 24), gemessen ab Ende des letzten Laufs. Ein manueller Trigger setzt den Timer zurück; trifft
er während eines Laufs ein, folgt genau ein weiterer Lauf direkt im Anschluss. Eine
neu angelegte Verbindung löst sofort einen nicht-blockierenden Ingest aus; das Löschen einer
Verbindung entfernt die semantische Schicht der Datenbank, ihren Katalog und ihre Embeddings.
Abschaltbar mit `ao.ingest.scheduler-enabled=false`.

**Entfernte Objekte.** Verschwindet eine Tabelle oder Spalte aus der Quelle, löscht der Ingest
neben den Katalogzeilen auch die zugehörigen ColumnAttributes und deren Embeddings. Terms bleiben
erhalten (sie können mehrere Tabellen und Datenbanken beschreiben).

**PII-Erkennung** (`PiiDetection`): Regeln aus `pii_detection/rules.py` (normalisierte Namen und
Typen, Muster mit hoher Sicherheit). Treffer bekommen den Tag `PII`, verarbeitete Spalten
`pii_processed = true`. Ein manuell entfernter Tag wird nicht neu gesetzt, weil verarbeitete Spalten
übersprungen werden. Der LLM-Fallback des Originals ist nicht portiert.

### 4.3 Semantische Kompilierung (`ao-semantic`)

`SemanticCompiler.compile` baut die Ontologie je Datenbank in vier Stufen und loggt am Ende eine
Zusammenfassungszeile (Tabellen, Terms, Semantic FKs, SQL Attributes, Bridges, Dauer):

| Stufe | Inhalt |
|---|---|
| **1 Taxonomie** (parallel je Tabelle, nur Tabellen ohne Term) | **Profiling** (`Profiler`): Stichprobe bis 1000 Zeilen über `sampleQuery`, `is_unique`, die 5 häufigsten Werte, bei Textspalten mit ≤ 25 Werten eine `SELECT DISTINCT`-Probe (abschaltbar über `distinct_value_probing_enabled`), keine Beispielwerte für Datum/Zeit/UUID und Strings über 30 Zeichen, Datumsformat-Erkennung (`DateFormats`), Sampling-Breaker nach 3 Fehlschlägen in Folge. <br>**FK-Erkennung** und Junction-Einstufung (LLM-Urteil plus strukturelle Evidenz). <br>**Term-Extraktion** per LLM (`TableTaxonomy`): Terms mit Name, Beschreibung, Synonymen und Spaltenzuordnung; Fallback-Name aus dem Tabellennamen. <br>**Commit in kanonischer Tabellenreihenfolge**, damit kollidierende Namen deterministisch vergeben werden. <br>**Embedding** von Terms und Column Attributes in `semantic_layer` (`SemanticEmbedder`, Textformat nach `semantic/embed.py`). |
| **2 Semantic FKs** | Fast Path über deklarierte FKs; sonst Vektorsuche nach den Top-5-ColumnAttributes anderer Tabellen und LLM-Auswahl. Danach Kanten ohne gemeinsame Werte entfernen und je Gruppe genau einen Default `use_in_join_paths = true` setzen. |
| **3 SQL Attributes** | Ausdrücke (Filter, Aggregationen, Funktionen) aus der Query-Historie der Tabellen eines Terms, Top 10 per LLM, Speicherung mit `source = 'sql'`. Ranking über `sql_query.total_counter` (Korrektur eines Bugs im Original, Flag `ao.semantic.rank-sql-by-usage`, Default `true`). |
| **4 Bridge Tables** | Reine FK-Tabellen finden, per LLM eine Join-SQL erzeugen und als SqlAttribute mit `source = 'bridgeTable'` am passenderen Term speichern; der Ziel-Term wird per Rerank-Modell gewählt. |

Die LLM-Nebenläufigkeit ist begrenzt (`SEMANTIC_LLM_MAX_INFLIGHT`, Default 6). Der Abbruch ist
**kooperativ**: Ein `CancellationToken` wird vor jeder Tabelle und zwischen den Stufen geprüft.

**Scheduler** (`SemanticCompilationService`): wartet auf den ersten Ingest, läuft danach alle 24 h
und auf Trigger. Jeder Lauf prüft `semantic_compilation_enabled` und tut nichts, wenn das Flag aus
ist. Start und Ende landen in `semantic_compilation_history`; danach werden Regeln angewandt und PII
auf Attribute übertragen.

- Einschalten mit `PUT /api/configurations/semantic-compilation` und `{"enabled": true}` stößt einen
  Lauf an, ohne auf ihn zu warten.
- `POST /api/semantic-compilation/reset` bricht einen laufenden Durchgang ab, löscht die
  semantische Schicht (aller oder einer Datenbank, inkl. Embeddings) und kompiliert neu, strikt in
  dieser Reihenfolge. 202 heißt „angenommen“, nicht „fertig“. Ohne `database_name` werden **alle**
  Datenbanken zurückgesetzt; ein unbekannter Name ergibt 404 und löscht nichts. Beim Reset einer
  einzelnen Datenbank bleiben Terms erhalten, die auch Tabellen anderer Datenbanken beschreiben
  (Terms werden über Name und Quelle zusammengeführt).
- `GET /api/semantic-compilation/status` liefert `calculated` (mindestens ein Term existiert),
  `running`, den letzten Erfolg und den letzten Fehler.
- `POST /api/semantic-compilation/trigger` ist eine dokumentierte Erweiterung (im Original nur
  intern).

Die Prompts liegen wörtlich übernommen unter `ao-semantic/src/main/resources/prompts/` (11 Dateien).

### 4.4 Vektorstore (`ao-vector`)

Zwei Collections (Namen mit optionalem Präfix `ao.vector.qdrant.collection-prefix`):

- `data_objects_layer`: Tabellen und Spalten des Katalogs,
- `semantic_layer`: Terms, ColumnAttributes, SqlAttributes, CustomAnalyses und Dashboard-Datasets
  (`label = 'Dataset'`).

| Implementierung | Einsatz | Eigenschaften |
|---|---|---|
| `QdrantVectorStore` (Standard, `ao.vector.store=qdrant`) | Betrieb | gRPC auf Port 6334, Cosine-Distanz, Payload-Indizes (`keyword`) auf `database_name`, `label`, `id`; Point-ID = fachliche UUID oder deterministische UUIDv5; Upserts in Batches mit `wait = true`; Start bricht ab, wenn eine Collection mit anderer Dimension existiert |
| `InMemoryVectorStore` (`ao.vector.store=memory`) | Tests, Demo | Brute-Force-Cosinus; mit `ao.vector.persist=true` in der H2-Tabelle `vector_record` gespeichert und beim Start geladen |

Nach außen arbeiten alle Implementierungen mit **Cosinus-Distanz** (kleiner ist besser), wie das
Original. Der Qdrant-Adapter rechnet `distance = 1 − score` um. Schwellwerte wie 0,75 (Entity
Coverage, Dataset-Auswahl) gelten auf dieser Skala. Ein gemeinsamer Vertragstest
(`VectorStoreContract`) läuft gegen beide Implementierungen.

Die Dimension ist konfigurierbar (`ao.vector.dimensions`, Default 2048, im Demo-Profil 768) und
muss zum Embedding-Modell passen. Zwischen Store und Qdrant gibt es keine gemeinsame Transaktion:
Erst wird der Store committet, dann Qdrant geschrieben. `POST /internal/vector/reconcile` gleicht
die IDs ab, löscht verwaiste Points und bettet fehlende Einträge nach.

### 4.5 LLM-, Embedding- und Rerank-Schicht (`ao-llm`)

**Vier Modellrollen** (`ModelRole`), je mit `endpoint`, `api-key`, `model` und optional
`structured-output`. Fehlende Werte fallen auf `ao.models.defaults.*` zurück.

| Rolle | Verwendung | Umgebungsvariablen |
|---|---|---|
| `REASONING` | Ontologie bauen, SQL schreiben, Antworttext | `REASONING_ENDPOINT/API_KEY/MODEL` |
| `NON_REASONING` | schnelle Schritte: Intent, Entitäten, Filter, SQL-Prüfung, Reparatur | `NON_REASONING_*` |
| `EMBED` | Indexierung und Suche | `EMBED_*` |
| `RERANK` | Ranking (Bridge Tables) | `RERANK_*` |
| Default | Fallback für alle Rollen | `DEFAULT_MODELS_ENDPOINT/API_KEY/MODEL` |

Alle Endpunkte sind **OpenAI-kompatibel** (NVIDIA NIM, vLLM, Ollama, LM Studio, LiteLLM, Azure
OpenAI). Ein leerer API-Key ist erlaubt; dann wird kein `Authorization`-Header gesendet.

**`LlmGateway`** ist die einzige Stelle für LLM-Aufrufe (NFR-ARC-04):

- `structured(role, messages, Class<T>)` füllt einen Java-Record (strict, unbekannte Felder schlagen
  fehl). Parse- und Validierungsfehler werden mit Backoff wiederholt (`ao.llm.max-retries`,
  Default 3; nach dem letzten Versuch ohne Pause). Für `NON_REASONING` folgt danach ein Fallback
  auf `REASONING`.
- `tryStructured(...)` liefert `Optional.empty()` statt einer Exception.
- `text(role, messages)` für Freitext.

`LangChain4jLlmGateway` setzt das auf dem OpenAI-Chatmodell von LangChain4j um. Der Modus für
strukturierte Ausgabe wird je Rolle **explizit** gewählt (statt des Modellpräfix-Tricks des
Originals):

| `structured-output` | Verhalten |
|---|---|
| `json_schema` | Response-Format mit JSON-Schema aus dem Record (`OutputSchema`) |
| `json_object` | JSON-Modus ohne Schema, Schema im Prompt |
| `tool_calling` | Function Calling mit `tool_choice` |
| `tool_calling_no_choice` | Function Calling ohne `tool_choice` (Claude-Modelle hinter Gateways, FR-LLM-06) |

Timeout je Aufruf `LLM_INVOKE_TIMEOUT_S` (Default 120 s), prozessweit höchstens `LLM_MAX_INFLIGHT`
(Default 6) parallele Aufrufe. `LlmMetrics` zählt Aufrufe, Fehler und Dauer im Speicher; eine
Anbindung an Micrometer ist vorbereitet (`setListener`), aber noch nicht verdrahtet.

**Embeddings** (`Embedder`):

- `OpenAiCompatibleEmbedder` (`ao.embedding.provider=openai`): eigener `RestClient`, sendet
  `input_type` (`query`/`passage`) und `truncate: END`; für Modelle in
  `ao.embedding.text-prefixed-models` stattdessen Textpräfixe `query: `/`passage: `; Batches von
  `ao.embedding.batch-size` (Default 32); bricht bei falscher Dimension mit klarer Meldung ab.
- `HashEmbedder` (`ao.embedding.provider=hash`): deterministisches, lokales Hash-Embedding ohne
  Netz, für Tests und die Offline-Demo. Für echte Ergebnisqualität ein echtes Modell verwenden.

**Rerank** (`RerankClient`) unterstützt beide Schemata nach URL-Endung: `…/rerank`
(`results[].relevance_score`) und `…/reranking` bzw. `…/ranking` (`rankings[].logit`).

**Prompts** (`Prompts`) lädt Ressourcen aus `prompts/*.txt` mit Platzhaltern `{{name}}`. Die
Dateien sind wörtlich aus dem Original exportiert (Werkzeug: `docs/tools/export_prompts.py`) und
tragen einen Herkunftskommentar.

**Konfigurationsbericht** (`ConfigurationReport`): Beim Start loggt das Backend die wirksame
Konfiguration: Profile, Store, Endpunkt/Modell/Modus je Rolle, die ersten Zeichen jedes API-Keys
samt Länge, Vektorstore, Verbindungen (Passwörter maskiert), Agent- und NL-Chart-Einstellungen.
Abschalten mit `ao.log-configuration=false`.

**`ScriptedLlmGateway`** spielt vorgegebene Antworten ab und ist die Grundlage der Tests ohne LLM.

### 4.6 Text-to-SQL-Agent (`ao-agent`)

#### Graph-Engine

`AgentGraph` ist eine schlanke Graph-Engine statt LangGraph: Knoten, feste und bedingte Kanten,
ein Recursion-Limit je Lauf (`ao.agent.recursion-limit`, Default 45). Je Knoten sendet sie ein
`step`-Event mit `phase: start` und eines mit `phase: end`. Das End-Event trägt den `thought`, den
genau dieser Knoten gerade geschrieben hat. Ändert sich das geprüfte SQL (`validated_sql`), folgt
ein `sql`-Event. Ein Abbruch über das `CancellationToken` wird vor jedem Knoten geprüft.

#### Aufbau des Graphen (`AgentRunner.buildGraph`)

```
question_intent ─▶ question_extraction ─▶ retrieve_candidates
                                              │
                         information ─────────┤
                              ▼               ▼
                     information_agent ─▶ END prepare_candidates
                                              ▼
                                construct_sql_from_candidates
                                   │ kein SQL            │ SQL
                                   ▼                     ▼
                     unconstructable_sql_response ◀─ validate_sql_query ◀────────────┐
                                   ▼        (Limit)      │ ok          │ Fehler      │
                                  END                    ▼             ▼             │
                                               execute_sql_query   reconstruct_sql ──┘
                                                 │ ok    │ Fehler ──▶ (reconstruct_sql)
                                                 ▼
                                      check_empty_like_result ── markiert ─▶ reconstruct_sql
                                                 ▼
                                        check_value_repair
                                       repaired │  regenerate │  sonst
                                  validate_sql_query  reconstruct_sql  ▼
                                                              format_and_respond ─▶ END
```

Jeder Fehlschlag zählt in `path_state.failed_attempts`. Nach `ao.agent.max-reconstruction-attempts`
(Default 7) Versuchen geht der Lauf nach `unconstructable_sql_response`. Die „Soft Checks“
(`check_empty_like_result`, `check_value_repair`) respektieren dasselbe Limit.

#### Knoten (`Nodes`)

| Knoten | Label in der UI | Aufgabe |
|---|---|---|
| `question_intent` | Classifying the question | Klassifiziert in `information`, `prediction`, `calculation` (bei `calculation` mit Subtyp und SQL-Vorlage aus `calculation_subtypes.json`), trennt Inline-Evidenz ab, erkennt eine Ziel-Datenbank im Text (Ähnlichkeit ≥ 0,8) |
| `question_extraction` | Understanding the question | Bereinigt die Frage, extrahiert bis zu `SQL_GEN_MAX_ENTITIES` (Default 5) Entitäten und das Subjekt; nutzt Glossar und Akronyme |
| `retrieve_candidates` | Retrieving candidates | Vektorsuche je Entität in `semantic_layer`, Wahl der Datenbank mit der höchsten Abdeckung, LLM-Relevanzfilter, Deduplizierung |
| `information_agent` | Answering from metadata | Metadatenfragen über eine Tool-Schleife mit 6 Read-only-Tools (`list_databases`, `list_tables`, `list_columns`, `list_terms`, `get_term`, `search_semantic_layer`) |
| `prepare_candidates` | Preparing candidates | Relevante Tabellen laden, Relevanzfilter, Join-Pfade (`JoinPathFinder`), relevante Queries und Analysen, Samples und Nullability nachladen |
| `construct_sql_from_candidates` | Constructing SQL | SQL-Synthese aus Kandidaten, Schema-Kontext, Join-Pfaden, Beispielen, Dialekt- und Projektionsregeln, Subtyp-Vorlage |
| `validate_sql_query` | Validating SQL and intent | Statische Prüfung (`SqlStaticChecks`: nur ein `SELECT`, parsebar, Katalognamen existieren, keine degenerierten Prädikate wie `1=0`, Quoting von Mixed-Case-Bezeichnern unter PostgreSQL), danach LLM-Intent-Validierung |
| `execute_sql_query` | Executing SQL | Ausführung über den Konnektor mit Timeout und Zeilenlimit (`ao.agent.max-result-rows`, Default 1000) |
| `check_empty_like_result` | Checking results | Bei leerem Ergebnis mit `LIKE` nicht-essenzielle Filter per LLM erkennen |
| `check_value_repair` | Checking values | Bei leerem Ergebnis Literale gegen Distinct-Werte abgleichen (Near-Miss, z. B. `wholesale` → `Wholesale`), sonst eine Neugenerierung |
| `reconstruct_sql` | Reconstructing SQL | Fehler klassifizieren (`FIXABLE` / `MISSING_DATA`), bei `MISSING_DATA` weitere Tabellen per Vektorsuche nachladen, alle Fehlversuche in den Prompt |
| `format_and_respond` | Formatting response | Markdown-Antwort mit Entitäts-Links `[[[name/id]]]`, Ergebnis, `sql_code` |
| `unconstructable_sql_response` | SQL could not be constructed | Erklärung, warum die Frage mit diesen Daten nicht beantwortbar ist |

Nicht umgesetzt sind `refine_evidence`, `validate_sql_values`, `precheck_combined` und der
Kumo-Zweig (`prepare_prediction_graph`, `kumo_predict`). Der Graph routet wie im Original bei
abgeschalteten Proben bzw. ohne Evidenz; das ist in `ABWEICHUNGEN.md` begründet.

**Dialektregeln** liegen als Prompt-Bausteine in `ao-agent/src/main/resources/prompts/dialect_*.txt`
(H2, MySQL, Oracle, PostgreSQL, Snowflake, SQLite, T-SQL). Oracle, T-SQL und H2 sind neu verfasst.

**Datenschutz in Prompts** (NFR-SEC-04): Beispielwerte von PII-Spalten gehen nur an das LLM, wenn
`ao.security.pii-samples-to-llm=true`. Werte, die wie Anweisungen aussehen, werden nie in einen
Prompt übernommen.

#### Lauf, Wiederholung und Fallbacks (`AgentRunner.run`)

1. **Initialzustand:** Systemnachricht mit dem heutigen Datum (aus `AoTime`, also ggf. der festen
   Uhr) und allen Custom Prompts, Hinweis „Antworte in der Sprache der Frage“. Akronyme gehen in
   `domain_rules` und `glossary`. `target_db` darf UUID oder Name sein; bei nur einem Konnektor wird
   er automatisch gesetzt. Ohne Konnektoren bricht der Lauf ab.
2. **Follow-up:** Mit Konversationshistorie (letzte fünf Runden, höchstens 12.000 Zeichen) schreibt
   das LLM eine kontextabhängige Frage in eine eigenständige um; bei einem LLM-Fehler bleibt die
   Originalfrage.
3. **Gesamtwiederholung:** Endet ein Lauf ohne SQL in `unconstructable`, startet der Graph einmal
   von vorn auf dem gesicherten Ausgangszustand (`MAX_FULL_PIPELINE_ATTEMPTS = 2`).
4. **Deterministischer Fallback:** Scheitert auch das, wird das letzte SQL ausgeführt, das die
   statische Prüfung bestand und nur an der Intent-Validierung scheiterte
   (`returned_deterministic_fallback: true`).
5. **Fehler:** Ein Knotenfehler oder das Recursion-Limit erzeugt ein `error`-Event mit `node` und
   `error_type`.

Logs tragen per MDC die Correlation-ID `ao.run` und den Knoten `ao.node`
(`[run=… node=…]` im Logmuster).

#### Weitere Agent-Bausteine

- **`JoinPathFinder`**: projiziert Semantic FKs und `HAS_ATTRIBUTE` auf ungerichtete
  Tabellenkanten (mit exakten Join-Spalten) und sucht per BFS. Zwei Spalten, die nur auf dasselbe
  Zielattribut zeigen, werden nicht direkt verbunden.
- **`EntityCoverageService`**: Grundlage von `POST /api/question-entity-coverage` und dem MCP-Tool
  `check_answerable`. Schneller Graph `question_extraction → retrieve_candidates → coverage_grade`,
  Distanzschwelle 0,75, Abdeckung = Anteil der Entitäten mit Treffer. Läufe sind serialisiert.
- **`ChartAdvisor`**: Chat-Visualisierung (FR-AGT-12) im Format von quasar-dashboard (NFR-QD-08).
  Das LLM entscheidet Tabelle oder Chart und liefert `{viz_type, params, mode: "sql"}`; erlaubt sind
  nur Typen mit genau einer Query (`bar`, `line`, `scatter`, `heatmap`, `big_number`, `table`).

### 4.7 Chat-Laufzeit (`ao-api`, Paket `chat`)

`POST /api/chat/completions` liefert einen **SSE-Stream**.

**Request-Body:**

| Feld | Bedeutung |
|---|---|
| `question` | Pflicht |
| `evidence` | optionale, verbindliche Zusatzinformation |
| `conversation_id` | UUID des Threads; ohne ID zustandslos (keine Historie, kein Chart-Schritt); eine unbekannte UUID legt die Konversation an |
| `prediction` | `true`/`false`/`null` (Vorhersagepfad ist nicht umgesetzt) |
| `target_db` | UUID oder Name einer Katalog-Datenbank |

Der Header `x-auto-ontology-source` bestimmt die Quelle in den Analytics (Default `api`, die
Web-UI sendet `app`, der MCP-Server `mcp`, das NL-Modul `nl-chart`).

**Events** (`data: {...}` je Zeile, Ende mit `data: [DONE]`):

| `type` | Inhalt |
|---|---|
| `step` | `node`, `label`, `phase` (`start`/`end`), `thought` (nur bei `end`) |
| `sql` | `node`, `sql`: das SQL, das die letzte Prüfung bestanden hat und gleich läuft |
| `result` | `answer`: `response`, `sql_code`, `sql_response_from_db`, `thoughts`, `question_type`, `target_db`, `standalone_question`, … |
| `charts` | `content` (Chart-Blöcke), `sql_response` – nur mit `conversation_id` und nach erfolgreicher Ausführung |
| `error` | `message`, `node`, `error_type`, `partial_answer` |

In Ruhephasen schickt der Server SSE-Kommentare `: ping`. Antwort-Header: `text/event-stream`,
`Cache-Control: no-cache, no-transform`, `X-Accel-Buffering: no`.

**Nebenläufigkeit** (`ChatService`):

- Je `conversation_id` ist genau ein Lauf aktiv. Eine zweite Anfrage bei verbundenem Client ergibt
  **409 „Conversation in progress“**; ein verwaister Lauf (Client weg) wird verdrängt.
- Läufe laufen auf Virtual Threads; Abbruch über `CancellationToken`, Interrupt und
  `Statement.cancel()`.
- `GET /api/chat/watch?conversation_id=…` hängt sich an einen laufenden Lauf an und liefert alle
  Events **von Beginn an** aus dem Puffer, danach live. Läuft nichts, endet der Stream sofort.
  Replay und Anmeldung sind ein atomarer Schritt: Ein Watcher verpasst kein Event und erhält auch
  dann `[DONE]`, wenn der Lauf während des Anhängens endet. Watcher bekommen wie der anfragende
  Client alle 15 s einen Ping.
- `POST /api/chat/cancel` bricht den Lauf einer Konversation ab.

**Speicherung im Backend** (läuft auch weiter, wenn der Client getrennt ist):

1. Beim Start: User-Nachricht speichern, Analytics-Zeile anlegen.
2. Nach `result`: Assistenten-Nachricht mit Antworttext und `sql_code`; Analytics vervollständigen.
3. Nach `charts`: zweite Assistenten-Nachricht. Mit Charts enthält `content` Fenced Blocks
   ` ```chart\n{viz_type, params, mode, dataset_id?}\n``` `; ohne Charts, aber mit Zeilen, stehen die
   Zeilen als JSON in `sql_response`.

Beispiel (ohne Token, die API hat keine Anmeldung):

```bash
curl -N -X POST localhost:8080/api/chat/completions \
     -H 'Content-Type: application/json' \
     -d '{"question":"How many wholesale customers are there?"}'
```

---

## 5. Datenmodell

Das Schema wird per Liquibase angelegt (`ao-store/src/main/resources/db/changelog/ao-changelog.yaml`,
`ao/001-schema.sql`). Unterstützte Store-Datenbanken: **PostgreSQL** (Standard) und **H2** (Demo,
`MODE=PostgreSQL`). Der Store nutzt einen eigenen Liquibase-Bean `aoLiquibase` mit eigenen
Changelog-Tabellen, damit er neben den Migrationen eines Hosts (z. B. quasar-dashboard) existieren
kann.

> Ein **persistenter** H2-Store darf nicht `DATABASE_TO_LOWER=TRUE` verwenden: Liquibase findet seine
> Changelog-Tabelle nach einem Neustart sonst nicht wieder. Der Store verweigert eine solche URL mit
> klarer Meldung; In-Memory-H2 ist erlaubt.

**Konventionen** (aus `schema.py` des Originals): IDs sind Text-UUIDs (`VARCHAR(36)`); eine
ungültige ID ergibt ein leeres Ergebnis bzw. 404, nie 500. Die Hierarchie Database → Schema →
Table → Column hängt über Parent-FKs mit `ON DELETE CASCADE`. Assoziationstabellen haben genau ein
`__` im Namen. Zertifizierungsflags sind `NOT NULL DEFAULT false`. `modified*` bleibt `NULL`, bis
ein Mensch speichert. Benutzerspalten enthalten die ID des festen Benutzers.

| Bereich | Tabellen |
|---|---|
| Katalog | `catalog_database`, `catalog_schema`, `catalog_table`, `catalog_column` |
| Beobachtete Beziehungen | `column__foreign_key` (deklarierte FKs, `last_seen`), `column__join`, `column__union` (aus der Query-Historie), `table__join` (aus Import) |
| Query-Historie | `sql_query`, `sql_query__table`, `sql_query__column` |
| Ontologie | `term`, `table__term`, `column_attribute`, `column_attribute__term`, `column__has_attribute`, `column__semantic_fk`, `sql_attribute` (`source` ∈ `manual`, `sql`, `table`, `bridgeTable`, `dataset`), `sql_attribute__term`, `sql_attribute__sql` |
| Analysen | `custom_analysis`, `custom_analysis__sql`, `pql_analysis`, `text_attribute`, `analysis` |
| Governance | `zone`, `zone_target`, `tag`, `tag_target`, `rule`, `rule__tag`, `user_group`, `user_group__member` |
| Kompilierung | `semantic_compilation_history` |
| App (im Original Frontend/Prisma) | `app_conversations`, `app_messages`, `app_prompts`, `app_configurations`, `app_acronyms`, `app_conversation_analytics` |
| Vektoren (nur In-Memory-Store mit Persistenz) | `vector_record` |
| NL-Charts | `ao_dataset_link`, `ao_dataset_column_link` |

Anmelde-Tabellen des Originals (`user`, `session`, `apikey`, `oauth*` …) gibt es nicht.

**Laufzeiteinstellungen** liegen in `app_configurations` und werden über die API geändert
(`RuntimeSettings`). Defaults, wenn kein Eintrag existiert:

| Schalter | API | Default |
|---|---|---|
| `semantic_compilation_enabled` | `/api/configurations/semantic-compilation` | `false` |
| `pii_detection_enabled` | `/api/configurations/pii-detection` | `false` |
| `distinct_value_probing_enabled` | `/api/configurations/distinct-value-probing` | `true` |
| `visualization_enabled` | `/api/configurations/visualization` | `true` |
| `sql_query_timeout_seconds` | `/api/configurations/sql-query-timeout` | 30 (1–3600) |

Das Demo-Profil schaltet Kompilierung, Visualisierung und PII-Erkennung ein.

---

## 6. REST-API

Alle öffentlichen Endpunkte liegen unter dem konfigurierbaren Präfix **`ao.api-prefix`**
(Standard `/api`, Umgebungsvariable `AO_API_PREFIX`). Jeder Controller trägt
`@RequestMapping("${ao.api-prefix:/api}")`; das Präfix steht nirgends sonst im Code. Pfade,
Methoden und JSON-Felder (snake_case) entsprechen `auto-ontology/docs/openapi/auto-ontology-api.json`
des Originals; ein Vertragstest prüft das (Abschnitt 12).

Das Präfix wird beim Start geprüft (`ApiPrefixValidator`): beginnt mit `/`, endet nicht mit `/`,
ist nicht leer, kollidiert nicht mit `/internal`, `/mcp`, `/actuator`, `/h2-console` und liegt
nicht unter `quasar-dashboard.api-prefix`. Ein ungültiger Wert bricht den Start ab.

Das generierte OpenAPI-Dokument steht unter `/v3/api-docs`.

| Gruppe | Endpunkte (mit Standardpräfix) | Controller |
|---|---|---|
| Health | `GET /api/health` | `CatalogController` |
| Chat | `POST /api/chat/completions` (SSE), `GET /api/chat/watch` (SSE), `POST /api/chat/cancel` | `ChatController` |
| Konversationen | `GET/POST /api/conversations`, `GET/PATCH/DELETE /api/conversations/{id}`, `POST /api/conversations/{id}/messages` | `AppController` |
| Analytics | `GET /api/analytics` | `AppController` |
| Akronyme | `GET/POST /api/acronyms`, `PATCH/DELETE /api/acronyms/{id}` | `AppController` |
| Custom Prompts | `GET/POST /api/custom-prompts`, `GET/PATCH/DELETE /api/custom-prompts/{id}` | `AppController` |
| Konfiguration | `GET/PUT /api/configurations/{visualization, pii-detection, semantic-compilation, sql-query-timeout, distinct-value-probing}` | `AppController` |
| Verbindungen | `GET/POST /api/connections`, `PUT/DELETE /api/connections/{database_name}`, `GET /api/connections/source`, `POST /api/connections/test` | `CatalogController` |
| Katalog | `GET /api/datasources/dbs`, `GET /api/schemas/{db_id}`, `GET /api/tables/{schema_id}`, `GET /api/columns/{table_id}`, `PATCH /api/nodes/{node_id}` | `CatalogController` |
| Kompilierung | `GET /api/semantic-compilation/status`, `POST /api/semantic-compilation/reset`, `POST /api/semantic-compilation/trigger` (Erweiterung) | `CatalogController` |
| Beantwortbarkeit | `POST /api/question-entity-coverage` | `CatalogController` |
| Terms | `GET /api/terms`, `GET/PATCH /api/terms/{id}`, `GET /api/terms/{id}/column-attributes`, `PATCH /api/terms/{id}/column-attributes/{attr_id}`, `GET /api/terms/{id}/sql-attributes` | `SemanticController` |
| SQL Attributes | `GET/POST /api/sql-attributes`, `POST /api/sql-attributes/validate`, `GET/PUT/PATCH/DELETE /api/sql-attributes/{id}`, `GET …/{id}/description-suggestion` | `SemanticController` |
| Custom Analyses | `GET/POST /api/custom-analyses`, `POST …/validate`, `PUT/DELETE …/{id}` | `SemanticController` |
| PQL Analyses | `GET/POST /api/pql-analyses`, `PUT/DELETE …/{id}` | `SemanticController` |
| Exploration | `GET /api/exploration/{graph, semantic-graph, edges}`, `GET …/nodes/{id}/relationships`, `GET …/{tables,columns,terms,column-attributes,sql-attributes,sql}/{id}/details`, `GET …/terms/{id}/path/{other_id}`, `GET …/tables/zones` | `ExplorationController` |
| Suche | `POST /api/search/global-search`, `POST /api/search/global-search/count` | `GovernanceController` |
| Tags | `GET/POST /api/tags`, `GET/PATCH/DELETE /api/tags/{id}`, `GET/POST /api/tags/{id}/targets`, `DELETE /api/tags/{id}/targets/{target_type}/{item_id}` | `GovernanceController` |
| Regeln | `GET/POST /api/rules`, `PATCH/DELETE /api/rules/{id}` | `GovernanceController` |
| Zonen | `GET/POST /api/zones`, `GET/PATCH/DELETE /api/zones/{id}`, `PATCH /api/zones/{id}/status` | `GovernanceController` |
| Gruppen, Owner | `GET/POST /api/groups`, `GET/PATCH/DELETE /api/groups/{id}`, `GET /api/owners` | `GovernanceController` |
| Import/Export | `POST /api/model/export`, `POST /api/model/import` (Multipart) | `ModelInterchangeController` |
| Demo (nur Profil `demo`) | `GET /api/demo/questions`, `POST /api/demo/reingest` | `DemoConfiguration` |
| NL-Charts | siehe Abschnitt 8 | `NlChartController` |

**Feste Pfade ohne Präfix** (`InternalController`):

| Pfad | Zweck |
|---|---|
| `GET /health` | Readiness: Store, Vektorstore, Modell; antwortet auch bei Ausfall mit Status statt 500 |
| `GET /health/live` | Liveness ohne Abhängigkeiten |
| `POST /internal/ingest`, `/internal/ingest/delete` | Ingest auslösen bzw. Daten einer Datenbank löschen |
| `POST /internal/semantic/compile`, `/reset`, `/stop`; `GET /internal/semantic/status` | Kompilierung steuern (Stop gibt es nur intern) |
| `POST /internal/vector/reconcile` | Abgleich Store ↔ Vektorstore |

Ist `ao.internal.secret` gesetzt, verlangen die `/internal/**`-Endpunkte den Header
`x-ao-internal-secret`; sonst antworten sie mit 404. Ohne Secret sind sie offen – sie gehören nur
ins private Netz.

**Fehlerformate** (FR-API-02): Routen, die im Original FastAPI bedient, antworten mit
`{"detail": "…"}` bzw. `{"detail": [Validierungsfehler]}` (422). Routen, die im Original Next.js
bedient (Konversationen, Akronyme, Custom Prompts, Konfiguration, Analytics), antworten meist mit
`{"error": "…"}` oder Klartext. Statuscodes: 400, 404, 409, 422, 502. 401 und 403 kommen nicht vor.
Alle Antworten tragen `Cache-Control: private, no-store`.

Wichtige Sonderfälle:

- `POST /api/sql-attributes` antwortet mit 201; Pflicht sind `name`, `description`, `expression`,
  `term_id`.
- `POST /api/sql-attributes/validate` und `/api/custom-analyses/validate`: ein Parse-Fehler ist
  **422**, nicht `valid: false`.
- `PATCH /api/sql-attributes/{id}` ändert nur Metadaten; `PUT` ersetzt den Ausdruck, parst ihn neu
  und schreibt das Embedding neu.
- Custom Analyses: Konflikte bei Name oder SQL ergeben 409.
- Import/Export: natives YAML (Terms, Attribute, SqlAttributes, Analysen). Das Format Apache Ossie
  antwortet mit 422.

---

## 7. MCP-Server

`ao-mcp` stellt einen **read-only** MCP-Server per Streamable HTTP unter dem festen Pfad `/mcp`
bereit. Er ist ein HTTP-Client der eigenen öffentlichen API (keine DB-Credentials), Basis-URL aus
`ao.mcp.api-base-url` (Standard im selben Prozess `http://localhost:${server.port}${ao.api-prefix}`).
MCP-Clients melden sich nicht an; die Aufrufe laufen als fester Benutzer. Abschalten mit
`ao.mcp.enabled=false`.

| Tool | Abbildung |
|---|---|
| `check_readiness` | fasst Kompilierungsstatus, Verbindungen und Datenbanken zu einem Urteil zusammen |
| `search_terms` | `GET /api/terms` |
| `get_term` | `GET /api/terms/{term_id}` |
| `get_term_columns` | `GET /api/terms/{term_id}/column-attributes` |
| `get_term_sql_attributes` | `GET /api/terms/{term_id}/sql-attributes` |
| `get_sql_attribute` | `GET /api/sql-attributes/{attr_id}` |
| `describe_table` | `GET /api/exploration/tables/{table_id}/details` |
| `check_answerable` | `POST /api/question-entity-coverage` |
| `ask_question` | `POST /api/chat/completions`, Stream lesen, Ergebnis als Antwort, SQL, Zeilen |

Die Tool-Beschreibungen sind wörtlich aus dem Original übernommen, weil sie das Verhalten fremder
Agenten steuern. Das optionale Tool `suggest_chart` (FR-NLC-22a, P3) gibt es noch nicht.

---

## 8. Charts aus natürlicher Sprache (quasar-dashboard)

### 8.1 Idee

quasar-dashboard stellt Charts nur über registrierte **Datasets** dar und speichert nur solche
Charts. Das Modul `ao-nl-chart` übersetzt eine Frage deshalb nicht in SQL, sondern in
**Chart-Parameter** (`ChartParams`) eines Datasets. Auto-Ontology liefert dafür Begriffe, Synonyme,
Kennzahlen, Spalten und Werte.

```
Frage ─▶ Dataset-Auswahl (Vektorsuche label='Dataset', Distanz ≤ 0,75)
      ─▶ LLM: viz_type + ChartParams (strukturiert, Viz-Katalog)
      ─▶ Validierung: Katalog → Spalten/Metriken/Rollen → query_context (buildQuery-Port)
                      → Kompilierung durch die Dashboard-Engine; Filterwerte gegen echte Werte
      ─▶ bei Fehler bis zu 2 Reparaturrunden; bleibt der Vorschlag ungültig: error-Event und Ende
      ─▶ chart_suggestion (Vorschau im QdChartRenderer)
      ─▶ Speichern ausschließlich über POST /api/v1/chart/ der Dashboard-API
```

| Modus | Wann | Ergebnis |
|---|---|---|
| **A, `semantic`** | Ein registriertes Dataset deckt die Frage ab | vollwertiger Dashboard-Chart, speicherbar, im Chart-Builder bearbeitbar |
| **B, `sql`** | Kein Dataset passt (das LLM wählt keines) – nicht nach einer gescheiterten Validierung | der Text-to-SQL-Agent liefert SQL und Zeilen; `SqlResults` wandelt sie in ein `QueryResult` für den gesteuerten Modus des Renderers; nur Ansicht, nicht speicherbar |

### 8.2 Bausteine

- **`DatasetSync`** (beim Start, nach jedem Ingest und per `POST …/sync`): verknüpft jedes
  `qd_dataset` mit seiner Katalogtabelle (`ao_dataset_link`), speichert Spaltenrollen
  (`type_generic`, `is_dttm`, `groupby`, `filterable`) in `ao_dataset_column_link`, übernimmt
  Beschreibungen, wo nichts zertifiziert ist, spiegelt gespeicherte Metriken als SqlAttributes mit
  `source = 'dataset'` und bettet je Dataset einen Datensatz `label = 'Dataset'` ein. Die Abbildung
  der Dashboard-Datenbanken auf AO-Verbindungen steht in `ao.dashboard.database-mapping` (darf n:1
  sein).
- **Viz-Katalog** `ao-nl-chart/src/main/resources/nl-chart/viz-catalog.json`: Daten-Controls,
  Defaults und „wann geeignet“ für die 8 eingebauten Typen (`bar`, `line`, `pie`, `big_number`,
  `scatter`, `heatmap`, `pivot_table`, `table`). Er ist handgeschrieben, nicht aus der Bibliothek
  generiert (siehe `ABWEICHUNGEN.md`), und über `GET …/viz-catalog` abrufbar.
- **`QueryContextBuilder`**: Java-Port von `plugin.buildQuery` der 8 eingebauten Typen. Für
  Viz-Typen des Hosts liefert er `null`; dann prüft nur der Client.
- **`ChartValidator`**: Validierung in der Reihenfolge von FR-NLC-11, Zeitbereiche über
  `TimeRange.parse` der Dashboard-Engine, Filterliterale gegen die echten Spaltenwerte
  (Groß-/Kleinschreibung und Tippfehler werden serverseitig repariert, Übersetzungen wie
  „Deutschland“ → `Germany` übernimmt das LLM per Prompt).
- `ao.nl-chart.dataset-allowlist` schränkt die Kandidaten fachlich ein;
  `ao.nl-chart.expose-metric-sql=true` zeigt dem LLM die SQL-Ausdrücke gespeicherter Metriken.

### 8.3 API

Präfix `ao.nl-chart.api-prefix`, Standard `${ao.api-prefix}/nl-chart`. Es darf nicht unter dem
Dashboard-Präfix (`/api/v1`) liegen.

| Endpunkt | Zweck |
|---|---|
| `POST /api/nl-chart/suggest` | Body `{question, dataset_id?, viz_type?, current_params?, conversation_id?, dashboard_id?}`. SSE-Stream: `step`-Events (`choose_dataset`, `build_chart`, `validate`, `repair`), dann `chart_suggestion` mit `{mode, dataset_id, viz_type, params, slice_name, description, explanation, assumptions, alternatives, query_context, warnings}` (Modus B zusätzlich `results`, `sql`), dann `[DONE]`; Fehler als `error`-Event |
| `POST /api/nl-chart/validate` | Prüft `{dataset_id, viz_type, params}` ohne LLM; Antwort `{valid, errors[], query_context}` |
| `GET /api/nl-chart/datasets` | Datasets (ggf. nach Allowlist) mit Abdeckung durch die Ontologie und Sync-Status |
| `POST /api/nl-chart/sync` | Dataset-Sync auslösen |
| `GET /api/nl-chart/viz-catalog` | Viz-Katalog (Erweiterung) |

Mit `current_params` beziehen sich Folgefragen („als Linie“, „nur EMEA“, „pro Woche“) auf den
bisherigen Vorschlag. Jede Anfrage wird in den Analytics mit `source = 'nl-chart'` protokolliert.

### 8.4 Voraussetzung und Grenze

Modus A läuft nur auf Dashboard-Datasets in **PostgreSQL und H2**. Für Oracle und SQL Server
braucht die Query-Engine von quasar-dashboard eigene Dialekte (FR-NLC-24); diese Änderung gehört in
das Repository quasar-dashboard und ist noch offen.

---

## 9. Frontend

pnpm-Workspace unter `frontend/` mit zwei Bibliotheken und einer Demo-App. quasar-dashboard wird
aus den Quellen des Nachbarrepositorys eingebunden.

### 9.1 `@jentsch/quasar-ontology` (`packages/ontology`)

Die Auto-Ontology-Oberfläche als **Komponentenbibliothek**: Seitenkomponenten plus Plugin. Die
Bibliothek kennt keine Routen; der Host bindet die Seiten in seine eigenen Routen ein.

```ts
import { createQuasarOntology } from '@jentsch/quasar-ontology'
app.use(createQuasarOntology({ baseUrl: '', apiPrefix: '/api', fetch?, headers? }))
```

- Jede Request-URL ist `baseUrl + apiPrefix + Pfad`. `apiPrefix` muss zu `ao.api-prefix` passen.
- Das Plugin stellt den API-Client per `useAo()` bereit und mischt die Texte (`ao.*`, DE/EN) in die
  vue-i18n-Instanz des Hosts (vue-i18n mit `legacy: false` muss vorher installiert sein).
- SSE wird über `fetch` mit `ReadableStream` gelesen (POST mit Body), robust gegen geteilte Chunks
  (`createSseParser`).

| Export | Inhalt |
|---|---|
| `AoChatPage` | Konversationsliste, Nachrichten, Live-Schritte aus `step`-Events, SQL, Ergebnistabelle, Charts, Abbrechen, Reattach per `watch`, Deep-Link `?focus=` |
| `AoTermsPage` | Glossar: Liste, Detail, Bearbeiten, Zertifizieren, ColumnAttributes, SqlAttributes |
| `AoDataPage` | Katalogbaum mit Beschreibung, Zertifizierung, Owner, Beispielwerten |
| `AoDiscoveryPage` | globale Suche |
| `AoAnalysisPage` | Custom Analyses und PQL Analyses |
| `AoExplorationPage` | Graph von Daten und Semantik (ECharts-Graph) |
| `AoAnalyticsPage` | Nutzungsbericht |
| `AoSettingsPage` | Abschnitte `connections`, `compilation`, `agent`, `semanticInput` (Akronyme, Custom Prompts), `pii`, `tags`, `rules`, `zones`, `groups`, `importExport` |
| `AoResultView` | Ergebnisanzeige (Tabelle, CSV, Chart über `QdChartRenderer`) |
| Hilfsfunktionen | `rowsToQueryResult`, `parseChartBlocks`, `toCsv`, `entityLinks` |

### 9.2 `@jentsch/quasar-dashboard-nl` (`packages/dashboard-nl`)

```ts
import { createQuasarDashboardNl, QdNlChartPrompt } from '@jentsch/quasar-dashboard-nl'
app.use(createQuasarDashboardNl({ baseUrl: '', apiPrefix: '/api/nl-chart' }))
```

`@jentsch/quasar-dashboard` ist peerDependency; die Dashboard-Bibliothek importiert nie aus diesem
Paket. `QdNlChartPrompt` bietet Eingabe (Strg+Enter sendet), Live-Fortschritt, Vorschau im
`QdChartRenderer`, Erklärung, Annahmen und Warnungen, Varianten aus `alternatives`, Folgefragen und
die Aktionen Speichern, Im Chart-Builder öffnen und Verwerfen. Texte im i18n-Namespace `qdnl`.

### 9.3 Demo-App (`apps/demo`)

Quasar-App, die beide Bibliotheken und quasar-dashboard mit **denselben Request-Optionen**
konfiguriert (`src/boot/plugins.ts`). Routen: `/chat`, `/discovery`, `/terms`, `/analysis`,
`/exploration`, `/data`, `/analytics`, `/settings/:section?`, `/ask` (NL-Chart-Prompt) und
`/explore` (Chart-Builder von quasar-dashboard).

| Variable | Wirkung | Default |
|---|---|---|
| `AO_BACKEND` | Ziel des Dev-Proxys | `http://localhost:8080` |
| `VITE_AO_API_PREFIX` | API-Präfix für Proxy und Plugins | `/api` |
| `VITE_API_BASE_URL` | Basis-URL aller Plugins | leer (gleiche Origin) |

Der Dev-Server läuft auf Port **9100** und leitet das API-Präfix an das Backend weiter.

---

## 10. Konfiguration

Alle Einstellungen liegen unter `ao.*` (`AoProperties`). Die Umgebungsvariablen des Originals sind
in `ao-common/src/main/resources/ao-defaults.properties` auf diese Schlüssel gemappt; die Datei hat
die niedrigste Priorität, explizite `ao.*`-Werte des Hosts gewinnen.

### 10.1 Infrastruktur

| Property | Umgebungsvariable | Default | Bedeutung |
|---|---|---|---|
| `server.address` | `SERVER_ADDRESS` | `127.0.0.1` | Bind-Adresse; im Docker-Image `0.0.0.0` |
| `server.port` | `SERVER_PORT` | `8080` | |
| `spring.datasource.*` | `POSTGRES_HOST/PORT/USER/PASSWORD/DATABASE` | `localhost:5432/auto_ontology` | Metadaten-Store |
| `ao.api-prefix` | `AO_API_PREFIX` | `/api` | Präfix der öffentlichen API |
| `ao.nl-chart.api-prefix` | – | `${ao.api-prefix}/nl-chart` | Präfix des NL-Moduls |
| `ao.user.id` / `ao.user.display-name` | – | `local` / `Local user` | fester Benutzer (Demo: `demo`) |
| `ao.clock.fixed-now` | `AO_FIXED_NOW` (Demo) | leer | festes „jetzt“ (ISO-Datum oder Instant); sonst Bean `quasarDashboardClock` bzw. System-UTC |
| `ao.log-configuration` | – | `true` | Konfigurationsbericht beim Start |
| `ao.internal.secret` | `AO_INTERNAL_SECRET` (Profil `worker`) | leer | Shared Secret für `/internal/**` |
| `ao.mcp.enabled` / `ao.mcp.api-base-url` | – | `true` / eigener Prozess | MCP-Server |

### 10.2 Vektorstore und Embedding

| Property | Umgebungsvariable | Default |
|---|---|---|
| `ao.vector.store` | `AO_VECTOR_STORE` | `qdrant` (Demo: `memory`) |
| `ao.vector.dimensions` | `AO_VECTOR_DIMENSIONS` | 2048 (Demo: 768) |
| `ao.vector.persist` | – | `false` (Demo: `true`, Tabelle `vector_record`) |
| `ao.vector.qdrant.host` / `port` | `QDRANT_HOST` / `QDRANT_PORT` | `localhost` / 6334 |
| `ao.vector.qdrant.api-key` | `QDRANT_API_KEY` | leer |
| `ao.vector.qdrant.use-tls`, `timeout`, `collection-prefix`, `upsert-batch-size`, `datatype` | – | `false`, 30 s, leer, 256, `float32` |
| `ao.embedding.provider` | `AO_EMBEDDING_PROVIDER` (Demo) | `openai` (Demo: `hash`) |
| `ao.embedding.send-input-type`, `batch-size`, `text-prefixed-models` | – | `true`, 32, `nemotron-3.5-embed-1b-nvfp4` |

### 10.3 Modelle

| Property | Umgebungsvariable |
|---|---|
| `ao.models.defaults.{endpoint, api-key, model}` | `DEFAULT_MODELS_ENDPOINT`, `DEFAULT_MODELS_API_KEY`, `DEFAULT_MODELS_MODEL` |
| `ao.models.reasoning.*` | `REASONING_ENDPOINT`, `REASONING_API_KEY`, `REASONING_MODEL` |
| `ao.models.non-reasoning.*` | `NON_REASONING_*` |
| `ao.models.embed.*` | `EMBED_*` |
| `ao.models.rerank.*` | `RERANK_*` |
| `ao.models.<rolle>.structured-output` | – (`json_schema`, `json_object`, `tool_calling`, `tool_calling_no_choice`) |
| `ao.llm.invoke-timeout-s`, `max-inflight`, `max-retries` | `LLM_INVOKE_TIMEOUT_S`, `LLM_MAX_INFLIGHT`, – (120, 6, 3) |

> **Achtung:** `ao-defaults.properties` enthält als Fallback für `DEFAULT_MODELS_*` einen konkreten
> lokalen Endpunkt (`http://localhost:8888/v1`), ein konkretes Modell und einen API-Key. Diese
> Werte greifen, sobald die Umgebungsvariablen fehlen. Für Betrieb und Weitergabe sollten sie durch
> leere Defaults ersetzt und über die Umgebung gesetzt werden; der Key gehört nicht ins Repository.

### 10.4 Ingest, Kompilierung, Agent, Sicherheit, Dashboard

| Property | Umgebungsvariable | Default |
|---|---|---|
| `ao.ingest.connection-strings` | `CONNECTION_STRINGS` | leer |
| `ao.ingest.connections[]` (`name`, `url` oder `datasource-bean`, `schemas`, `table-allow-regex`, `table-deny-regex`) | – | – |
| `ao.ingest.interval-hours` / `scheduler-enabled` | `INGEST_INTERVAL_HOURS` | 24 / `true` |
| `ao.semantic.interval-hours` / `scheduler-enabled` | – | 24 / `true` |
| `ao.semantic.llm-max-inflight` | `SEMANTIC_LLM_MAX_INFLIGHT` | 6 |
| `ao.semantic.rank-sql-by-usage` | – | `true` |
| `ao.agent.sql-gen-max-entities` | `SQL_GEN_MAX_ENTITIES` | 5 |
| `ao.agent.max-reconstruction-attempts` / `recursion-limit` | – | 7 / 45 |
| `ao.agent.max-result-rows` / `probe-budget` | – | 1000 / 20 |
| `ao.agent.validate-sql-values`, `prefer-certified` | – | `false` |
| `ao.agent.db-probe-proactive`, `db-probe-join-path-check`, `db-probe-jsonb-path-check` | `DB_PROBE_PROACTIVE`, `DB_PROBE_JOIN_PATH_CHECK`, `DB_PROBE_JSONB_PATH_CHECK` | `false` |
| `ao.agent.detect-vacuous-group-by` | `DETECT_VACUOUS_GROUP_BY` | `false` |
| `ao.agent.intent-validation-joins-validated-elsewhere` | `INTENT_VALIDATION_JOINS_VALIDATED_ELSEWHERE` | `false` |
| `ao.security.pii-samples-to-llm` | – | `false` |
| `ao.nl-chart.dataset-allowlist`, `expose-metric-sql` | – | leer, `false` |
| `ao.dashboard.database-mapping` | – | leer |

Laufzeitschalter (Visualisierung, PII, Kompilierung, Timeout, Distinct-Proben) werden **nicht**
über Properties, sondern über die API gesetzt (Abschnitt 5).

### 10.5 Profile von `ao-app`

| Profil | Datei | Wirkung |
|---|---|---|
| default | `application.yml` | PostgreSQL-Store, Qdrant, Bind an `127.0.0.1`, Actuator `health,info,metrics,prometheus`, Async-Timeout 1800 s, Uploads bis 50 MB |
| `demo` | `application-demo.yml` | H2-Datei-Store `./data/ao-store`, In-Memory-Vektorstore mit Persistenz, Hash-Embedding, Dimension 768, fester Benutzer `demo`, `fixed-now = 2026-06-30`, Verbindung `demo_sales` (H2 in-memory, `QUERY_HISTORY` per Deny-Regex ausgeschlossen), H2-Konsole unter `/h2-console` (nur localhost) |
| `qdrant` | `application-qdrant.yml` | Qdrant statt In-Memory, z. B. `demo,qdrant` |
| `worker` | `application-worker.yml` | Instanz nur für Ingest und Kompilierung; Secret für `/internal/**`. Die API-Instanz schaltet dann `ao.ingest.scheduler-enabled` und `ao.semantic.scheduler-enabled` ab |

---

## 11. Betrieb

### 11.1 Voraussetzungen

- JDK 21, Gradle-Wrapper liegt bei (`backend/gradlew`).
- Node.js 22.12+, 24 oder 26 und pnpm ≥ 9 (`packageManager: pnpm@12.16.1`).
- Das Nachbarrepository `C:\Projekte\Text2SQL\quasar-dashboard` (Composite Build und Frontend-Quellen).
- Ein OpenAI-kompatibler LLM-Endpunkt. Ingest und Kompilierung laufen auch ohne LLM (mit
  Fallback-Terms), Chat-Antworten brauchen eins.

### 11.2 Demo „Sales“ ohne externe Dienste

```bash
cd backend
DEFAULT_MODELS_ENDPOINT=http://localhost:11434/v1 DEFAULT_MODELS_MODEL=qwen3 \
  ./gradlew :ao-app:bootRun --args='--spring.profiles.active=demo'
cd ../frontend && pnpm install && pnpm dev          # http://localhost:9100
```

Unter Windows erledigen das `demo_backend.ps1` und `demo_frontend.ps1`. `bootRun` läuft mit dem
Projektverzeichnis als Arbeitsverzeichnis; die H2-Dateien landen in `data/`.

Beim Start erzeugt `DemoDatabase` die Quell-DB „Sales“ (Seed 42, Zeitraum 2024-07-01 bis
2026-06-30, Schemas `SALES`, `APPLICATION`, `WAREHOUSE`, `PURCHASING`, Demo-Query-Historie mit
`END_TIME` innerhalb der letzten 24 Stunden). Danach laufen Ingest, PII-Erkennung und Kompilierung.
Eingebaute Demo-Fälle: ein fehlender deklarierter FK (`SALES.INVOICES.CUSTOMER_ID`), ein Datum als
Text (`YYYYMMDD`), Groß-/Kleinschreibungsfallen bei Kategorien, Kommentare, eine reine
FK-Tabelle für die Bridge-Demo.

Ablauf:

1. *Settings → Semantic Compilation* zeigt den Status, bis „Is Ready“ erscheint.
2. *Terms* zeigt die erzeugten Begriffe; einen Term zertifizieren.
3. *Exploration* zeigt den Graphen.
4. *Chat* mit den Vorschlagsfragen (`GET /api/demo/questions`, Quelle `demo/questions.yaml` mit
   erwarteten Werten).
5. Die `curl`-Abfrage aus Abschnitt 4.7 ausführen.
6. Optional einen MCP-Client mit `http://localhost:8080/mcp` verbinden.

`POST /api/demo/reingest` liest die Demo-Datenbank neu ein. Ein vollständiger Reset
(`/api/demo/reset`) fehlt; zum Zurücksetzen das Backend stoppen und `data/ao-store.*` löschen.

Mit Qdrant: `docker compose -f demo/docker-compose.demo.yml up` (Profil `demo,qdrant`).

### 11.3 Kombinierte Demo mit quasar-dashboard

```bash
cd backend && ./gradlew :ao-dashboard-demo:bootRun
cd ../frontend && pnpm dev                          # /ask und /explore
```

Der Host enthält das quasar-dashboard-Backend (API `/api/v1`, `fixed-now 2026-06-30T00:00:00Z`),
dessen Beispieldaten (`sales`, `web_traffic`, `employees`, `inventory`) in einer H2-In-Memory-DB mit
`DATABASE_TO_LOWER=TRUE`, Auto-Ontology unter `/api` und die NL-Chart-API unter `/api/nl-chart`.
Auto-Ontology ingestet die Analyse-DB über die `DataSource`-Bean `dataSource`, beschränkt auf die
vier Faktentabellen. Da diese denormalisiert und ohne FKs sind, liefern die Stufen Semantic FKs und
Bridge Tables erwartbar nichts. Das Golden Set liegt in `demo/nl-chart-questions.yaml`.

### 11.4 Docker

```bash
(cd backend && ./gradlew :ao-app:bootJar) && (cd frontend && pnpm install && pnpm build)
docker compose up --build        # PostgreSQL 17.6 + Qdrant 1.20.0 + Backend + Frontend
```

- Die Images werden aus vorher lokal gebauten Artefakten erzeugt, weil der Gradle-Build das
  Nachbarrepository braucht.
- Backend: `eclipse-temurin:21-jre`, eigener System-User, `SERVER_ADDRESS=0.0.0.0`.
- Frontend: `nginx:1.29-alpine` mit Content-Security-Policy, SSE-tauglichem Proxy
  (`proxy_buffering off`, 900 s Read-Timeout) für `/api/` und `/mcp`.
- Veröffentlichte Ports nur auf `127.0.0.1`: Frontend 9000, Backend 8080, Qdrant 6333/6334.
- Umgebungsvariablen: `POSTGRES_PASSWORD`, `AO_VECTOR_DIMENSIONS`, `CONNECTION_STRINGS`,
  `DEFAULT_MODELS_*`, `EMBED_*`.

Ein Helm-Chart gibt es nicht.

### 11.5 Worker-Betrieb

Für getrennte Instanzen startet eine Instanz mit Profil `worker` (Ingest und Kompilierung,
`/internal/**` mit `AO_INTERNAL_SECRET`), die API-Instanz mit
`ao.ingest.scheduler-enabled=false` und `ao.semantic.scheduler-enabled=false`. Beide nutzen
denselben Store und denselben Vektorstore.

### 11.6 Beobachtbarkeit

- **Health:** `/health` (Readiness: Store, Vektorstore samt Collections, Modell), `/health/live`
  (Liveness), `GET /api/health`, Actuator unter `/actuator/health` mit Probes.
- **Metriken:** Micrometer, `/actuator/metrics` und `/actuator/prometheus` (Standardmetriken von
  Spring Boot; LLM-Zähler sind noch nicht angebunden).
- **Logs:** Correlation-ID je Chat-Lauf und Knotenname im Logmuster; Dauer je Knoten auf INFO;
  Zusammenfassung je Kompilierung; Konfigurationsbericht beim Start. Credentials werden maskiert.

---

## 12. Build und Tests

```bash
cd backend && ./gradlew test             # ohne Docker: H2 + eingebettetes PostgreSQL
cd backend && ./gradlew integrationTest  # Testcontainers: Qdrant, Oracle Free, SQL Server
cd frontend && pnpm test && pnpm typecheck && pnpm --filter ao-demo typecheck && pnpm build
```

`pnpm test` führt die Vitest-Suiten beider Bibliotheken aus (`@jentsch/quasar-ontology` und
`@jentsch/quasar-dashboard-nl`).

Compiler-Optionen `-parameters -Xlint:all,-processing,-serial -Werror`. Tests laufen mit
`user.timezone=UTC` und englischer Locale. JaCoCo erzeugt XML- und HTML-Berichte unter
`<modul>/build/reports/jacoco/`. Tests mit dem Tag `integration` laufen nur im Task
`integrationTest`.

| Test | Modul | Prüft |
|---|---|---|
| `StoreSchemaTest` | `ao-store` | Schema und Round-Trips auf H2 und eingebettetem PostgreSQL, wiederholbare Migration, Reset einer Datenbank bei geteilten Terms |
| `InMemoryVectorStoreTest`, `QdrantVectorStoreIT` (über `VectorStoreContract`) | `ao-vector` | gleicher Vertrag für beide Vektorstores (Reihenfolge, Distanzen) |
| `LangChain4jLlmGatewayTest`, `ConfigurationReportTest`, `PromptsTest` | `ao-llm` | Structured Output je Modus, Jackson-3-Modus, Maskierung im Bericht, Füllen der Prompt-Platzhalter |
| `ReadOnlySqlTest` | `ao-connectors` | Read-only-Regel inklusive schreibender CTEs und `FOR UPDATE` auf jeder Ebene; Datenbankname aus der JDBC-URL |
| `H2SourceTest`, `H2UpperCaseSourceTest`, `PostgresSourceTest`, `OracleSourceIT`, `SqlServerSourceIT` (über `ConnectorContract`) | `ao-connectors` | gleicher Konnektor-Vertrag je Engine (Spalten, Typen, `is_nullable` Boolean, PKs, FKs, Views) |
| `IngestServiceTest` | `ao-ingestion` | Ingest, Diff, Historie, Embeddings; ColumnAttributes entfernter Spalten, Löschen einer Datenbank samt semantischer Schicht, Trigger während eines Laufs |
| `SemanticResetTest` | `ao-semantic` | Reset einer, aller und einer unbekannten Datenbank |
| `SqlStaticChecksTest` | `ao-agent` | statische SQL-Prüfung: Katalognamen, Pseudospalten, Aliase, Read-only, PostgreSQL-Quoting |
| `QueryContextBuilderTest` | `ao-nl-chart` | `buildQuery`-Port je Viz-Typ |
| `DemoEndToEndTest` | `ao-app` | Demo-Ende-zu-Ende mit gestubbtem LLM: Ingest, Kompilierung, Chat |
| `OpenApiContractTest` | `ao-app` | generiertes OpenAPI gegen die Referenz des Originals (Allowlist für entfallene Anmelde-Endpunkte) |
| `CustomApiPrefixTest` | `ao-app` | mit `ao.api-prefix=/ao` antwortet alles unter `/ao/…`, `/api/…` ergibt 404; ungültige Präfixe brechen den Start ab |
| `McpServerTest` | `ao-app` | MCP-Server und Tools |
| `NlChartIntegrationTest` | `ao-dashboard-demo` | NL-Chart-Pipeline gegen die echte Dashboard-Engine |
| `sse.test.ts` | Frontend-Pakete | SSE-Parser (geteilte Chunks, `[DONE]`), Ergebnis-Hilfsfunktionen, `newUuid` |

Letzter dokumentierter Lauf (Testberichte vom 2026-11-12): alle 78 Tests des Standard-Builds grün,
ein Test übersprungen; im Frontend 6 Vitest-Tests grün. Die Integrationstests sind geschrieben, wurden aber ohne Docker noch nicht
ausgeführt. Die JaCoCo-Schwellen 90/80 sind konfiguriert, aber nicht an `check` gebunden und werden
noch nicht erreicht.

---

## 13. Erweitern und Einbetten

### 13.1 Als Bibliothek in einen eigenen Host

```kotlin
dependencies {
    implementation("de.jentsch.autoontology:ao-spring-boot-starter:0.1.0-SNAPSHOT")
    implementation("de.jentsch.autoontology:ao-nl-chart:0.1.0-SNAPSHOT") // nur mit quasar-dashboard
}
```

Der Host liefert eine `DataSource` für den Store (PostgreSQL oder H2) und setzt die `ao.*`-Werte.
Liegt quasar-dashboard im selben Host, prüft der Start, dass `ao.api-prefix` und
`ao.nl-chart.api-prefix` nicht mit `quasar-dashboard.api-prefix` kollidieren, und die Uhr
`quasarDashboardClock` wird übernommen. Der Host richtet keine Security ein (NFR-QD-06).

### 13.2 Beans ersetzen

Die Auto-Konfigurationen deklarieren ihre Beans mit `@ConditionalOnMissingBean`. Typische
Ersetzungen:

| Bean | Zweck |
|---|---|
| `CurrentUser` | einzige Andockstelle für eine spätere Anmeldung |
| `LlmGateway` | anderer LLM-Anbieter oder Test-Stub (`ScriptedLlmGateway`) |
| `Embedder` | anderes Embedding-Modell |
| `VectorStore` | anderer Vektorstore (Vertragstest `VectorStoreContract` muss bestehen) |

### 13.3 Neuen Quell-Konnektor ergänzen

1. Unterklasse von `JdbcSourceDatabase` in `ao-connectors` mit `dialect()`, `quote()`, `qualify()`,
   `sampleQuery()`, `limit()` und ggf. `getQueries()` bzw. `getColumnComments()`.
2. In `Connectors` über JDBC-URL-Schema und Produktnamen registrieren, Treiber im Version Catalog.
3. Prompt-Regelblock `ao-agent/src/main/resources/prompts/dialect_<name>.txt` anlegen.
4. Test von `ConnectorContract` ableiten; Docker-basierte Tests mit `@Tag("integration")`.

### 13.4 Prompts ändern

Die Prompts sind wörtlich aus dem Original übernommen und tragen eine Herkunftszeile. Erst nach
messbarer Verbesserung (Demo-Fragen, Golden Set) ändern. Platzhalter haben die Form `{{name}}`.

### 13.5 Regressionen prüfen

`demo/questions.yaml` (Chat) und `demo/nl-chart-questions.yaml` (NL-Charts) enthalten die
erwarteten Ergebnisse. Nach Änderungen am Agenten oder an den Prompts gegen ein echtes LLM laufen
lassen.

---

## 14. Umsetzungsstand gegenüber den Anforderungen

Übersicht je Abschnitt des Anforderungsdokuments. Details und Begründungen der Abweichungen stehen
in [`ABWEICHUNGEN.md`](ABWEICHUNGEN.md).

| Abschnitt | Stand | Anmerkungen |
|---|---|---|
| 3 Architektur | umgesetzt | Monolith mit 14 Modulen, Starter, `worker`-Profil; MCP läuft im selben Prozess (kein eigenes Profil `mcp`, abschaltbar per Property); kein separates Modul `ao-catalog` (in `ao-ingestion`) |
| 4 Datenmodell, Qdrant | umgesetzt | PostgreSQL und H2; Oracle/SQL Server als Store (P3) nicht |
| 5 Konnektoren | umgesetzt | PostgreSQL, Oracle, SQL Server, H2, `DataSource`-Bean; Metadaten über `DatabaseMetaData`; weitere Engines (P3) nicht; Vault nicht |
| 6 Ingestion | umgesetzt | |
| 7 Kompilierung | umgesetzt | alle 4 Stufen, Ranking-Korrektur per Flag |
| 8 LLM-Schicht | umgesetzt | LangChain4j im Jackson-3-Modus, 4 Structured-Output-Modi |
| 9 Agent | Kern umgesetzt | ohne `refine_evidence`, `validate_sql_values`, `precheck_combined`, Value Anchors, Kumo, interaktiven Modus; Information-Agent mit 6 statt 13 Tools |
| 10 Chat | umgesetzt | 409, Watch, Cancel, Persistenz im Backend |
| 11 REST-API | umgesetzt | OpenAPI-Vertragstest; `trigger` und `viz-catalog` als Erweiterungen |
| 12 fester Benutzer | umgesetzt | keine Spring Security, kein 401/403 |
| 13 Governance | weitgehend | PII nur regelbasiert; Import/Export nur natives YAML (Ossie → 422) |
| 14 MCP | umgesetzt | 9 Tools; `suggest_chart` (P3) fehlt |
| 15 Frontend | weitgehend | alle Seiten als Bibliothek; Exploration mit ECharts-Graph statt sigma.js; keine Playwright-Tests |
| 16 Konfiguration | umgesetzt | Vault-, Kumo- und Interaktiv-Variablen ohne Wirkung |
| 17 NFR | weitgehend | CSP im Nginx; Helm-Chart fehlt |
| 18 Tests | teilweise | Unit-, Vertrags-, E2E-Tests vorhanden; JaCoCo-Schwellen nicht erzwungen; Integrationstests ungeprüft; kein Benchmark-Modus |
| 19 Demo | umgesetzt | Offline-Modus mit `ScriptedLlmGateway` nur in Tests; `/api/demo/reset` fehlt (nur `reingest`); Store-Datei heißt `ao-store` statt `ao-meta` |
| 23 NL-Charts | Modus A und B (Anzeige) umgesetzt | Viz-Katalog handgeschrieben; Paket `dashboard-nl` in diesem Repository; Oracle/SQL-Server-Dialekte in quasar-dashboard (FR-NLC-24) offen; MSW-Mock-Handler und Screenshot-Baselines fehlen |

---

## 15. Lizenz und Herkunft

Das Projekt steht unter der **Apache License 2.0** (`LICENSE`). `NOTICE` und `OPEN_SOURCE_NOTICE`
nennen die aus NVIDIA Auto-Ontology übernommenen Teile; jede dieser Stellen ist mit „Derived from
NVIDIA Auto-Ontology“ markiert:

- LLM-Prompts (wörtlich, `ao-agent/…/prompts`, `ao-semantic/…/prompts`),
- Taxonomie der Berechnungs-Subtypen (`calculation_subtypes.json`),
- LLM-Ausgabeschemata (Feldnamen und Beschreibungen),
- Knotenlabels, Graph-Topologie und Routing des Agenten,
- Vorlagen der Embedding-Texte, PII-Regeln, Datumsformat-Erkennung,
- MCP-Tool-Beschreibungen, Metadatenschema.

Neue Dateien tragen den Header `SPDX-License-Identifier: Apache-2.0`.

---

## 16. Glossar

| Begriff | Bedeutung |
|---|---|
| **Term** | Geschäftsbegriff, der eine oder mehrere Tabellen repräsentiert (z. B. „Customer“), mit Beschreibung und Synonymen |
| **Column Attribute** | Eigenschaft eines Terms, die auf eine konkrete Spalte zeigt |
| **SQL Attribute** | benannter, wiederverwendbarer SQL-Ausdruck an einem Term (`source`: `manual`, `sql`, `table`, `bridgeTable`, `dataset`) |
| **Semantic FK** | semantische Join-Kante: eine Spalte referenziert ein Column Attribute einer anderen Tabelle |
| **Bridge Table** | reine FK-Tabelle (n:m), deren Join als SQL Attribute hinterlegt wird |
| **Custom Analysis** | kuratierte, benannte SQL-Abfrage, die der Agent als Beispiel nutzt |
| **Zertifizierung** | menschliche Freigabe von Name, Beschreibung oder Ausdruck |
| **`data_objects_layer` / `semantic_layer`** | Vektor-Collections für Katalog bzw. Ontologie |
| **Entity Coverage** | Anteil der Entitäten einer Frage, für die die Ontologie Treffer hat (Beantwortbarkeit) |
| **Rekonstruktion** | Reparatur eines fehlgeschlagenen SQL durch den Agenten (höchstens 7 Versuche) |
| **Dataset** | in quasar-dashboard registrierte Tabelle mit Spalten und Metriken; Grundlage jedes Dashboard-Charts |
| **ChartParams** | Parameter eines Dashboard-Charts (`viz_type`, Metriken, Dimensionen, Filter, Zeitbereich) |
| **Modus A / B** | NL-Chart über ein Dataset (speicherbar) bzw. über Agent-SQL (nur Ansicht) |
| **Fester Benutzer** | die konfigurierte Identität (`ao.user.id`), unter der alle Anfragen laufen |
