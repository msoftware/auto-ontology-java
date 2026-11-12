# Entwicklerhandbuch Backend – Übersicht und Einstieg

Dieses Handbuch richtet sich an Entwickler, die am Backend von Auto-Ontology (Java/Spring Boot)
arbeiten. Es ergänzt die fachliche Projektdokumentation [`../DOKUMENTATION.md`](../DOKUMENTATION.md)
um Architektur- und Implementierungsdetails. Zu jedem Gradle-Modul gibt es ein eigenes Dokument
unter [`module/`](module/). Das Frontend (Vue/Quasar) beschreibt das
[Frontend-Entwicklerhandbuch](frontend/README.md).

| Wenn du … | dann lies |
|---|---|
| eine Umgebung aufsetzen oder konfigurieren willst | Abschnitt 3 („Externe Dienste und Konfiguration“) |
| neu im Projekt bist | Abschnitte 1–5 dieses Dokuments, danach [`ao-common`](module/ao-common.md) und [`ao-agent`](module/ao-agent.md) |
| eine Funktion ändern willst | Abschnitt 7 („Wo finde ich was?“) und das Dokument des betroffenen Moduls |
| die Architektur verstehen willst | Abschnitte 4, 6 und 8, danach die Moduldokumente in Abhängigkeitsreihenfolge |
| einen Fehler suchst | Abschnitt 9 („Fallstricke“) und den Abschnitt „Fallstricke“ im Moduldokument |
| Code zum Abschreiben suchst | Abschnitt 11 („Kochbuch“) und die Abschnitte „Code-Beispiele“ der Moduldokumente |

---

## 1. Das System in fünf Sätzen

1. Auto-Ontology liest die Metadaten von Quell-Datenbanken ein (**Ingest**) und legt sie in einem
   eigenen Metadaten-Store ab.
2. Ein LLM leitet daraus eine **Ontologie** ab: Geschäftsbegriffe (Terms), deren Attribute,
   semantische Joins und wiederverwendbare SQL-Ausdrücke (**Kompilierung**).
3. Katalog und Ontologie werden als Embeddings in einen **Vektorstore** geschrieben.
4. Ein **Agent-Graph** beantwortet Fragen: Kandidaten per Vektorsuche finden, SQL erzeugen, prüfen,
   reparieren, read-only ausführen, Antwort formulieren – gestreamt per SSE.
5. Ein **NL-Chart-Modul** übersetzt Fragen in Chart-Parameter für quasar-dashboard.

Das Ganze ist ein Nachbau von NVIDIA Auto-Ontology (Python). Pfade, JSON-Felder, Knotennamen und
Prompts sind bewusst gleich geblieben. Wenn eine Entscheidung im Code merkwürdig wirkt, steht die
Begründung fast immer im Javadoc (mit Anforderungs-ID wie `FR-ING-01`) oder im Originalcode, auf den
der Javadoc verweist.

---

## 2. In 15 Minuten lauffähig

### 2.1 Voraussetzungen

- JDK 21 (der Gradle-Toolchain-Mechanismus lädt es bei Bedarf).
- Das Nachbarrepository `C:\Projekte\Text2SQL\quasar-dashboard`. `backend/settings.gradle.kts` bindet
  `../../quasar-dashboard/backend` als **Composite Build** ein. Ohne das Verzeichnis lässt sich das
  Projekt nicht konfigurieren.
- Für Chat-Antworten: ein OpenAI-kompatibler LLM-Endpunkt (Ollama, vLLM, LM Studio, NIM …).
  Ingest, Kompilierung (mit Fallback-Namen) und alle Tests laufen ohne LLM.

### 2.2 Bauen und testen

```bash
cd backend
./gradlew build                 # kompiliert alles mit -Werror, führt die Tests ohne Docker aus
./gradlew :ao-agent:test        # nur ein Modul
./gradlew integrationTest       # Testcontainers (Qdrant, Oracle Free, SQL Server); braucht Docker
```

### 2.3 Demo starten

```bash
cd backend
DEFAULT_MODELS_ENDPOINT=http://localhost:11434/v1 DEFAULT_MODELS_MODEL=qwen3 DEFAULT_MODELS_API_KEY= \
  ./gradlew :ao-app:bootRun --args='--spring.profiles.active=demo'
```

> Setze `DEFAULT_MODELS_*` immer explizit. `ao-common/src/main/resources/ao-defaults.properties`
> enthält sonst Fallback-Werte für einen lokalen Endpunkt samt API-Key (siehe Abschnitt 9).

Das Demo-Profil braucht keine externen Dienste: H2-Store in `data/ao-store.mv.db`, In-Memory-
Vektorstore, Hash-Embedding, Quell-DB „Sales“ in H2. Nach dem Start:

```bash
curl -s localhost:8080/api/health
curl -s localhost:8080/api/semantic-compilation/status      # warten bis "calculated": true
curl -s 'localhost:8080/api/terms?limit=5'
curl -N -X POST localhost:8080/api/chat/completions -H 'Content-Type: application/json' \
     -d '{"question":"How many wholesale customers are there?"}'
```

Den Store zurücksetzen: Backend stoppen, `data/ao-store.*` löschen.

### 2.4 In der IDE debuggen

Main-Klasse `de.jentsch.autoontology.app.AoApplication`, Profil `demo`, Arbeitsverzeichnis =
Projektwurzel `auto-ontology-java` (dort landen `data/` und wird `.semantic_summaries/` gesucht).
Gute erste Breakpoints:

| Breakpoint | Was man sieht |
|---|---|
| `IngestService.doRun` | ein kompletter Ingest-Lauf |
| `SemanticCompiler.compile` | die vier Kompilierungsstufen |
| `AgentGraph.run` (Schleifenkopf) | jeder Knotenwechsel des Agenten mit dem `AgentState` |
| `LangChain4jLlmGateway.call` | jeder LLM-Aufruf mit Prompt und Rohantwort |
| `ChatService.execute` | ein Chat-Lauf von Anfang bis Ende |

---

## 3. Externe Dienste und Konfiguration

Auto-Ontology ist kein Programm, das allein läuft: Es verbindet sich mit Datenbanken, mit einem
Vektorstore und mit KI-Modellen. Dieses Kapitel erklärt, **welche Dienste es gibt, wozu das System
sie braucht, welche davon Pflicht sind** und wie man sie konfiguriert. Wer nur die Demo starten will,
braucht davon fast nichts (Abschnitt 3.1); im Betrieb kommen PostgreSQL, Qdrant und echte Modelle
dazu.

### 3.1 Landkarte der Dienste

```
                         ┌────────────────────────────── Auto-Ontology (ein Java-Prozess) ───────┐
 Browser ── HTTP/SSE ───▶│  REST-API /api  ·  NL-Charts /api/nl-chart  ·  MCP /mcp  ·  /actuator  │◀── MCP-Clients
 (Quasar-Frontend)       │                                                                        │    (Claude, IDEs)
                         └───┬──────────────┬───────────────────┬──────────────────┬──────────────┘
                             │ JDBC         │ gRPC :6334        │ HTTP (OpenAI-API) │ JDBC (nur lesend)
                             ▼              ▼                   ▼                   ▼
                     Metadaten-Store     Vektorstore        Modell-Endpunkte     Quell-Datenbanken
                     PostgreSQL / H2     Qdrant / In-Memory  LLM · Embedding ·    PostgreSQL · Oracle ·
                     (Pflicht)           (Pflicht)           Rerank               SQL Server · H2
                                                             (LLM für Antworten   (mindestens eine)
                                                              nötig)
```

| Dienst | Wozu Auto-Ontology ihn braucht | Pflicht? | Ersatz in der Demo | Wichtigste Einstellungen |
|---|---|---|---|---|
| **Metadaten-Store** (PostgreSQL) | speichert Katalog, Ontologie, Chats, Einstellungen | ja | H2-Datei `data/ao-store` | `spring.datasource.*` / `POSTGRES_*` |
| **Vektorstore** (Qdrant) | Ähnlichkeitssuche über Tabellen, Spalten und Begriffe | ja | In-Memory-Store | `ao.vector.*` / `QDRANT_*` |
| **LLM** (OpenAI-kompatibel) | baut die Ontologie, schreibt und prüft SQL, formuliert Antworten | für Antworten ja | – (ohne LLM nur Ingest und Fallback-Terms) | `ao.models.*` / `DEFAULT_MODELS_*`, `REASONING_*`, `NON_REASONING_*` |
| **Embedding-Modell** (OpenAI-kompatibel) | wandelt Texte in Vektoren für die Suche | ja | lokales Hash-Embedding | `ao.embedding.provider`, `EMBED_*`, `ao.vector.dimensions` |
| **Rerank-Modell** | wählt bei Bridge-Tabellen den passenden Begriff | nein | – | `RERANK_*` |
| **Quell-Datenbanken** | die Daten, über die Fragen gestellt werden | mindestens eine | H2 „Sales“ im Speicher | `CONNECTION_STRINGS`, `ao.ingest.connections`, UI |
| **quasar-dashboard** | Bibliothek für Charts und NL-Charts; Quellcode wird beim Bauen gebraucht | zum Bauen ja | – | Nachbarverzeichnis `../quasar-dashboard` |
| **OpenAPI** (springdoc) | maschinenlesbare Beschreibung der eigenen REST-API | eingebaut | – | `springdoc.api-docs.path` |
| **MCP-Clients** | externe Agenten nutzen die Ontologie | nein | – | `ao.mcp.*` |
| **Actuator / Prometheus** | Health-Checks und Metriken für den Betrieb | eingebaut, Abruf optional | – | `management.*` |
| **Docker / Testcontainers** | Images, Compose-Betrieb, Integrationstests | nein | – | `docker-compose.yml`, `./gradlew integrationTest` |

Mindestbedarf je Betriebsart:

| Betriebsart | Externe Dienste |
|---|---|
| Tests (`./gradlew test`) | keine (H2, eingebettetes PostgreSQL, In-Memory-Vektoren, Skript-LLM) |
| Demo ohne Antworten | keine |
| Demo mit Chat | **ein** LLM-Endpunkt |
| Demo „wie Betrieb“ | LLM, Embedding-Modell, Qdrant (`demo,qdrant`) |
| Betrieb | PostgreSQL, Qdrant, LLM, Embedding-Modell, Quell-DBs; optional Rerank |

### 3.2 Die Dienste im Einzelnen

#### Metadaten-Store (PostgreSQL oder H2)

**Was er ist:** die eigene relationale Datenbank von Auto-Ontology – nicht zu verwechseln mit den
Quell-Datenbanken, über die Fragen gestellt werden.

**Wozu:** Hier liegt alles, was Auto-Ontology weiß oder erzeugt: der eingelesene Katalog (Schemas,
Tabellen, Spalten, Schlüssel, beobachtete SQL-Statements), die Ontologie (Terms, Attribute,
Semantic FKs, SQL Attributes), Governance (Tags, Regeln, Zonen, Gruppen), Chat-Verläufe und
Analytics sowie die Laufzeitschalter. Ohne Store startet die Anwendung nicht.

**Wie er angebunden ist:** über die primäre `DataSource` von Spring (`spring.datasource.*`). Beim Start
legt Liquibase das Schema an bzw. migriert es (Tabellen `AO_DATABASECHANGELOG*`). Details:
[`ao-store`](module/ao-store.md).

| Variante | Wann | URL |
|---|---|---|
| PostgreSQL (empfohlen 17.x) | Betrieb, Compose | `jdbc:postgresql://<host>:5432/auto_ontology` |
| H2 als Datei | Demo | `jdbc:h2:file:./data/ao-store;MODE=PostgreSQL` |
| H2 im Speicher | Tests, Dashboard-Demo | `jdbc:h2:mem:…;MODE=PostgreSQL` |

```bash
POSTGRES_HOST=db POSTGRES_PORT=5432 POSTGRES_DATABASE=auto_ontology \
POSTGRES_USER=ao POSTGRES_PASSWORD=… java -jar ao-app.jar
```

Rechte: Der Store-Benutzer braucht `CREATE` im Schema (Liquibase legt Tabellen an). Für die schnelle
Teilstring-Suche versucht Liquibase, die Erweiterung `pg_trgm` anzulegen; fehlt das Recht dazu, läuft
der Start trotzdem weiter (`failOnError:false`), die Suche ist dann nur langsamer.

#### Vektorstore (Qdrant)

**Was er ist:** eine Datenbank für **Embeddings** – Zahlenvektoren, die die Bedeutung eines Textes
darstellen. Texte mit ähnlicher Bedeutung haben ähnliche Vektoren. [Qdrant](https://qdrant.tech) ist
ein eigenständiger Dienst, der solche Vektoren speichert und sehr schnell die ähnlichsten zu einem
Suchvektor findet.

**Wozu:** Eine Frage wie „Umsatz der Großkunden im Juni“ enthält kein Wort, das exakt einem
Tabellennamen entspricht. Die Vektorsuche findet trotzdem `SALES.INVOICE_LINES.EXTENDED_PRICE`
und den Term „Customer“ mit der Kategorie „Wholesale“. Konkret nutzen ihn:

| Wer | Wofür |
|---|---|
| Agent, `retrieve_candidates` | passende Terms, Attribute, SQL Attributes, Analysen zu den Entitäten einer Frage |
| Agent, `reconstruct_sql` | fehlende Tabellen nachladen |
| Entity Coverage, MCP `check_answerable` | Beantwortbarkeit einer Frage |
| Kompilierung, Stufe 2 | Kandidaten für semantische Joins |
| NL-Charts | passendes Dashboard-Dataset |

Es gibt zwei Collections: `data_objects_layer` (Tabellen und Spalten, gefüllt beim Ingest) und
`semantic_layer` (Ontologie und Datasets, gefüllt bei der Kompilierung).

**Wie er angebunden ist:** offizieller Java-Client über **gRPC auf Port 6334**. Port 6333 ist die
REST-API von Qdrant, die Auto-Ontology selbst nicht nutzt, die aber zum Nachsehen praktisch ist
(Weboberfläche unter `http://localhost:6333/dashboard`). Client und Server sind auf **1.20.0**
gepinnt.

```bash
docker run -p 127.0.0.1:6333:6333 -p 127.0.0.1:6334:6334 -v qdrant:/qdrant/storage qdrant/qdrant:v1.20.0
curl -s localhost:6333/collections            # nach dem ersten Start: data_objects_layer, semantic_layer
```

| Einstellung | Default | Bedeutung |
|---|---|---|
| `ao.vector.store` (`AO_VECTOR_STORE`) | `qdrant` (Demo: `memory`) | `qdrant` oder `memory` |
| `ao.vector.dimensions` (`AO_VECTOR_DIMENSIONS`) | 2048 (Demo: 768) | **muss exakt** der Ausgabegröße des Embedding-Modells entsprechen |
| `ao.vector.qdrant.host` / `port` (`QDRANT_HOST`/`QDRANT_PORT`) | `localhost` / 6334 | gRPC-Adresse |
| `ao.vector.qdrant.api-key` (`QDRANT_API_KEY`) | leer | Dienst-Schlüssel, falls Qdrant einen verlangt |
| `ao.vector.qdrant.use-tls` | `false` | TLS für gRPC |
| `ao.vector.qdrant.collection-prefix` | leer | mehrere Instanzen teilen sich einen Qdrant |

**Ersatz:** `ao.vector.store=memory` hält alle Vektoren im Arbeitsspeicher (mit
`ao.vector.persist=true` zusätzlich in der Store-Tabelle `vector_record`). Das reicht für Demo und
Tests, nicht für große Kataloge. Details: [`ao-vector`](module/ao-vector.md).

#### Modell-Endpunkte (OpenAI-kompatible API)

**Was „OpenAI-kompatibel“ heißt:** Viele Modellserver bieten dieselbe HTTP-Schnittstelle wie OpenAI an
(`POST /v1/chat/completions`, `POST /v1/embeddings`). Auto-Ontology spricht nur diese Schnittstelle.
Welches Modell dahinter läuft, ist egal – ein lokales Open-Source-Modell oder ein Cloud-Dienst.

| Server | typische Basis-URL | Hinweis |
|---|---|---|
| Ollama | `http://localhost:11434/v1` | lokal, einfach; API-Key leer lassen |
| vLLM | `http://localhost:8000/v1` | lokal/GPU-Server, schnell |
| LM Studio | `http://localhost:1234/v1` | lokal mit GUI |
| NVIDIA NIM | `https://integrate.api.nvidia.com/v1` bzw. eigener NIM | Vorbild des Originals |
| LiteLLM, Azure OpenAI, Gateways | je nach Installation | auch für Claude oder GPT über einen Gateway |

> Nicht verwechseln: **OpenAI-API** = Schnittstelle zu den Modellen (ausgehend).
> **OpenAPI** = Beschreibungsformat der *eigenen* REST-API (siehe unten).

**Wozu – die vier Rollen:** Das System unterscheidet Modelle nach Aufgabe. Jede Rolle hat Endpunkt,
API-Key und Modellname; fehlende Werte fallen auf `DEFAULT_MODELS_*` zurück. Im einfachsten Fall
setzt man nur die drei `DEFAULT_MODELS_*`-Variablen.

| Rolle | Variablen | Wofür | Anforderungen an das Modell |
|---|---|---|---|
| `REASONING` | `REASONING_ENDPOINT/API_KEY/MODEL` | Ontologie bauen (Terms, Beschreibungen, Joins), SQL schreiben, Metadatenfragen, NL-Charts | stark, gutes SQL, zuverlässig strukturierte Ausgabe |
| `NON_REASONING` | `NON_REASONING_*` | schnelle Schritte: Frage klassifizieren, Entitäten, Kandidatenfilter, SQL-Prüfung und -Reparatur, Follow-ups | schnell und günstig; bei Fehlern fällt das System auf `REASONING` zurück |
| `EMBED` | `EMBED_*` | Texte → Vektoren für die Suche | Embedding-Modell (siehe unten) |
| `RERANK` | `RERANK_*` | Bridge-Tabellen dem besseren Term zuordnen | Rerank-Modell; optional |

Ein Chat-Lauf macht typischerweise 6–15 LLM-Aufrufe, die Kompilierung mehrere je Tabelle. Mehr dazu,
was ohne LLM noch funktioniert: Ingest läuft vollständig; die Kompilierung erzeugt Terms aus
Tabellennamen ohne Beschreibungen; Chat-Antworten scheitern.

**Strukturierte Ausgabe:** Fast alle Aufrufe verlangen JSON in einem festen Schema. Wie das
angefordert wird, hängt vom Server ab und wird je Rolle eingestellt:

| `ao.models.<rolle>.structured-output` | Wann verwenden |
|---|---|
| `json_schema` (Default) | Server unterstützt `response_format` mit JSON-Schema (OpenAI, vLLM, neuere Ollama-Versionen) |
| `json_object` | Server kennt nur den einfachen JSON-Modus; das Schema steht dann im Prompt |
| `tool_calling` | Server unterstützt Function Calling besser als JSON-Schema |
| `tool_calling_no_choice` | wie `tool_calling`, ohne `tool_choice` – z. B. Claude hinter einem Gateway |

Faustregel: mit `json_schema` starten; erscheinen im Log wiederholt
`Structured LLM call … failed`, auf `json_object` wechseln.

Weitere Einstellungen: `LLM_INVOKE_TIMEOUT_S` (Default 120 s je Aufruf), `LLM_MAX_INFLIGHT` (Default
6 parallele Aufrufe im ganzen Prozess), `ao.llm.max-retries` (Default 3). Details:
[`ao-llm`](module/ao-llm.md).

#### Embedding-Modell

**Wozu:** Jede Tabelle, Spalte und jeder Begriff wird beim Ingest bzw. bei der Kompilierung einmal
eingebettet; jede Frage bei der Suche. Index und Suche müssen **dasselbe Modell** verwenden.

| Einstellung | Wert |
|---|---|
| `ao.embedding.provider` (`AO_EMBEDDING_PROVIDER`) | `openai` (echtes Modell) oder `hash` (lokal, offline, nur für Demo/Tests) |
| `EMBED_ENDPOINT`, `EMBED_MODEL`, `EMBED_API_KEY` | Endpunkt des Embedding-Modells (sonst `DEFAULT_MODELS_*`) |
| `ao.vector.dimensions` | Vektorgröße des Modells, z. B. 768 für `nomic-embed-text`, 1024 für `bge-m3` oder `mxbai-embed-large`; maßgeblich ist die Angabe des Modells |

Wechselt man das Modell oder die Dimension, passen die gespeicherten Vektoren nicht mehr: Qdrant-
Collections bzw. `data/` löschen und neu ingesten. Der Start bricht mit einer Meldung ab, wenn die
Dimension nicht passt – das ist Absicht.

Das Hash-Embedding erkennt nur ähnliche **Wörter**, keine Synonyme („Kunde“ ≠ „customer“). Für
realistische Antwortqualität ein echtes Modell verwenden.

#### Rerank-Modell (optional)

Wird nur in Stufe 4 der Kompilierung genutzt, um für eine Bridge-Tabelle den besser passenden Term
zu wählen. Ohne Rerank-Modell nimmt das System den ersten Kandidaten. Beide gängigen Schnittstellen
werden unterstützt (URL endet auf `/rerank` bzw. `/reranking` oder `/ranking`).

#### Quell-Datenbanken

**Wozu:** Das sind die Fachdatenbanken, über die Fragen gestellt werden. Auto-Ontology liest ihre
Metadaten (Ingest), zieht Stichproben (Kompilierung) und führt das erzeugte SQL aus (Chat). Es
schreibt **nie** in eine Quell-Datenbank.

Unterstützt: PostgreSQL, Oracle, SQL Server, H2. Verbindungen kommen aus drei Quellen:

```bash
# 1. Umgebungsvariable, komma- oder zeilengetrennt, optional mit Namen
CONNECTION_STRINGS='sales=jdbc:postgresql://db:5432/sales?user=ro&password=…'
```

```yaml
# 2. application-*.yml, mit Scoping oder einer vorhandenen DataSource-Bean
ao:
  ingest:
    connections:
      - name: erp
        url: jdbc:oracle:thin:@//ora:1521/ERP
        schemas: [SALES, MASTERDATA]
        table-deny-regex: ^(TMP_|BAK_)
      - name: analytics
        datasource-bean: analyticsDataSource
```

3. Über die UI (*Settings → Connections*) bzw. `POST /api/connections`.

Für Fragen zählen UI-Verbindungen zuerst, für den Ingest die konfigurierten (siehe
[`ao-ingestion`](module/ao-ingestion.md)).

**Immer einen reinen Lesebenutzer verwenden.** Beispielrechte:

| Engine | Lesen | Zusätzlich für die Query-Historie |
|---|---|---|
| PostgreSQL | `GRANT CONNECT`, `USAGE` auf die Schemas, `SELECT ON ALL TABLES IN SCHEMA …` | Rolle `pg_read_all_stats`, damit `pg_stat_activity` die Statements anderer Sitzungen zeigt |
| Oracle | `CREATE SESSION`, `SELECT` auf die Tabellen | `SELECT` auf `V_$SQL` (sonst bleibt die Historie leer) |
| SQL Server | `db_datareader`, `VIEW DEFINITION` | `VIEW DATABASE STATE` und aktivierter Query Store |

Die Query-Historie ist optional: Ohne sie fehlen nur die SQL Attributes aus beobachteten Abfragen.

#### quasar-dashboard (Nachbarrepository)

**Wozu:** quasar-dashboard ist eine eigene Dashboard-Bibliothek (Backend und Frontend). Auto-Ontology
nutzt ihren Chart-Renderer für alle Charts und baut mit `ao-nl-chart` „Charts aus Fragen“ für deren
Datasets. Das Backend bindet `../quasar-dashboard/backend` als Composite Build ein, das Frontend
nutzt `../quasar-dashboard/packages/dashboard` direkt aus den Quellen. Das Verzeichnis muss deshalb
**zum Bauen** vorhanden sein, auch wenn man das NL-Modul nicht nutzt. Zur Laufzeit ist es nur in
`ao-dashboard-demo` bzw. einem Dashboard-Host aktiv.

#### OpenAPI (springdoc)

**Was es ist:** OpenAPI ist ein Standardformat, das eine REST-API maschinenlesbar beschreibt
(Pfade, Methoden, Parameter, JSON-Formen). springdoc erzeugt dieses Dokument automatisch aus den
Controllern.

**Wozu:**
- Nachschlagen und Code-Generierung für Clients: `GET http://localhost:8080/v3/api-docs` (JSON);
  laden lässt es sich in Swagger Editor, Postman, Insomnia oder Bruno.
- **Vertragstest:** `OpenApiContractTest` vergleicht das erzeugte Dokument mit der API-Beschreibung
  des Originals (`auto-ontology/docs/openapi/auto-ontology-api.json`). So fällt sofort auf, wenn ein
  Pfad fehlt oder sich ändert.

Eine Swagger-Oberfläche ist nicht eingebaut (nur das API-Modul von springdoc). Pfad ändern mit
`springdoc.api-docs.path`.

#### MCP-Clients

MCP (Model Context Protocol) ist ein Standard, über den KI-Agenten Werkzeuge anderer Systeme
aufrufen. Auto-Ontology bietet unter `http://localhost:8080/mcp` neun **nur lesende** Werkzeuge an
(Begriffe suchen, Tabellen beschreiben, Beantwortbarkeit prüfen, Fragen stellen). Ein Client wie
Claude Desktop oder eine IDE trägt nur diese URL ein. Abschalten: `ao.mcp.enabled=false`. Details:
[`ao-mcp`](module/ao-mcp.md).

#### Actuator und Prometheus

Spring Boot Actuator stellt Betriebsendpunkte bereit:

| Endpunkt | Wozu |
|---|---|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Probes für Kubernetes/Compose |
| `/actuator/metrics`, `/actuator/prometheus` | JVM-, HTTP- und Pool-Metriken für Prometheus/Grafana |
| `/health`, `/health/live`, `/api/health` | eigene Health-Checks von Auto-Ontology: Store, Vektorstore, LLM konfiguriert |

#### Docker, Compose und Testcontainers

- `docker-compose.yml` startet PostgreSQL, Qdrant, Backend und Frontend (Nginx); alle Ports nur auf
  `127.0.0.1`. Die Images werden aus lokal gebauten Artefakten erzeugt.
- `demo/docker-compose.demo.yml` startet Demo-Backend und Qdrant.
- `./gradlew integrationTest` startet per Testcontainers Qdrant, Oracle Free und SQL Server – dafür
  muss Docker laufen. Der normale Build braucht kein Docker.

#### Frontend-Dev-Server und Nginx

Der Quasar-Dev-Server (`pnpm dev`, Port 9100) leitet `/api` an `AO_BACKEND`
(Default `http://localhost:8080`) weiter. Im Docker-Betrieb übernimmt Nginx diese Rolle – mit
abgeschaltetem Puffer und 900 s Timeout, damit die SSE-Streams der Chat-Antworten durchkommen. Ein
eigener Reverse Proxy braucht dieselben Einstellungen.

### 3.3 Wie die Konfiguration funktioniert

Auto-Ontology nutzt die normale Spring-Boot-Konfiguration. Wer Spring nicht kennt, braucht vier
Dinge:

**1. Woher Werte kommen (höchste Priorität zuerst):**

| Priorität | Quelle | Beispiel |
|---|---|---|
| 1 | Kommandozeile | `--ao.vector.store=memory` bzw. `--args='--ao.vector.store=memory'` bei `bootRun` |
| 2 | Umgebungsvariablen | `AO_VECTOR_STORE=memory` |
| 3 | `application-<profil>.yml` | `ao-app/src/main/resources/application-demo.yml` |
| 4 | `application.yml` | `ao-app/src/main/resources/application.yml` |
| 5 | `ao-defaults.properties` | Abbildung der Variablen des Originals (`DEFAULT_MODELS_*` …) |
| 6 | Defaults im Code | `@DefaultValue` in `AoProperties` |

Eigene Dateien lassen sich mit `--spring.config.additional-location=file:./config/` dazuladen,
z. B. eine nicht eingecheckte `application-local.yml`.

**2. Umgebungsvariablen und Properties sind dasselbe:** Spring übersetzt Namen automatisch
(„Relaxed Binding“): `ao.vector.qdrant.host` ⇔ `AO_VECTOR_QDRANT_HOST`,
`ao.ingest.connections[0].name` ⇔ `AO_INGEST_CONNECTIONS_0_NAME`. Zusätzlich gibt es die kurzen
Namen des Originals (`QDRANT_HOST`, `CONNECTION_STRINGS`, `DEFAULT_MODELS_MODEL` …), die in
`application.yml` bzw. `ao-defaults.properties` gemappt sind.

**3. Profile** bündeln Einstellungen: `--spring.profiles.active=demo` lädt zusätzlich
`application-demo.yml`. Mehrere Profile mit Komma: `demo,qdrant`.

**4. Zwei Arten von Einstellungen:**

| Art | Wo | Ändern | Beispiele |
|---|---|---|---|
| Start-Konfiguration | Properties, Umgebung | Neustart | Store, Vektorstore, Modelle, Verbindungen, Präfixe |
| Laufzeitschalter | Tabelle `app_configurations` | API oder UI (*Settings*), sofort wirksam | Kompilierung an/aus, PII-Erkennung, Visualisierung, SQL-Timeout, Distinct-Proben |

Die Laufzeitschalter sind beim ersten Start **aus** bzw. auf Default (Kompilierung aus!). Einschalten:

```bash
curl -X PUT localhost:8080/api/configurations/semantic-compilation \
     -H 'Content-Type: application/json' -d '{"enabled": true}'
```

Das Demo-Profil schaltet Kompilierung, Visualisierung und PII-Erkennung automatisch ein.

**Was tatsächlich gilt, zeigt das Log:** Beim Start schreibt `ConfigurationReport` einen Block mit
Profilen, Store, Endpunkt/Modell/Modus je Rolle, gekürzten API-Keys, Vektorstore, Verbindungen
(Passwörter maskiert) und Agent-Einstellungen. Das ist der erste Blick bei jedem
Konfigurationsproblem.

Die vollständige Liste aller Einstellungen steht in
[`../DOKUMENTATION.md`](../DOKUMENTATION.md), Abschnitt 10.

### 3.4 Rezepte

**a) Demo komplett offline** – nur Ingest und Ontologie aus Tabellennamen, keine Antworten:

```bash
./gradlew :ao-app:bootRun --args='--spring.profiles.active=demo'
```

**b) Demo mit lokalem Ollama** – mit Antworten; Embedding bleibt Hash:

```bash
ollama pull qwen3
DEFAULT_MODELS_ENDPOINT=http://localhost:11434/v1 DEFAULT_MODELS_MODEL=qwen3 DEFAULT_MODELS_API_KEY= \
  ./gradlew :ao-app:bootRun --args='--spring.profiles.active=demo'
```

**c) Demo mit echtem Embedding und Qdrant** – Verhalten wie im Betrieb:

```bash
ollama pull nomic-embed-text
docker run -d -p 127.0.0.1:6333:6333 -p 127.0.0.1:6334:6334 qdrant/qdrant:v1.20.0
DEFAULT_MODELS_ENDPOINT=http://localhost:11434/v1 DEFAULT_MODELS_MODEL=qwen3 DEFAULT_MODELS_API_KEY= \
AO_EMBEDDING_PROVIDER=openai EMBED_MODEL=nomic-embed-text AO_VECTOR_DIMENSIONS=768 \
  ./gradlew :ao-app:bootRun --args='--spring.profiles.active=demo,qdrant'
```

Vorher `data/` löschen, wenn die Demo schon mit Hash-Embedding lief.

**d) Betrieb mit PostgreSQL, Qdrant, vLLM und getrennten Rollen** – als `config/application-local.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://db.intern:5432/auto_ontology
    username: ao
    password: ${AO_DB_PASSWORD}
ao:
  user: { id: team-data, display-name: Data Team }
  vector:
    store: qdrant
    dimensions: 1024
    qdrant: { host: qdrant.intern, port: 6334 }
  embedding: { provider: openai }
  models:
    defaults:      { endpoint: http://vllm.intern:8000/v1, api-key: "", structured-output: json_schema }
    reasoning:     { model: Qwen/Qwen3-32B }
    non-reasoning: { model: Qwen/Qwen3-8B }
    embed:         { endpoint: http://embed.intern:8001/v1, model: BAAI/bge-m3 }
  ingest:
    connections:
      - name: sales
        url: jdbc:postgresql://sales.intern:5432/sales?user=ao_reader&password=${SALES_PW}
        schemas: [public]
```

```bash
java -jar ao-app.jar --spring.config.additional-location=file:./config/ --spring.profiles.active=local
```

**e) Claude oder GPT über einen Gateway (LiteLLM o. Ä.):**

```bash
REASONING_ENDPOINT=https://gateway.intern/v1 REASONING_MODEL=claude-sonnet REASONING_API_KEY=… \
AO_MODELS_REASONING_STRUCTURED_OUTPUT=tool_calling_no_choice \
  java -jar ao-app.jar
```

Der Modellname ist der, den der Gateway erwartet. Die übrigen Rollen können weiter auf ein lokales
Modell zeigen (`DEFAULT_MODELS_*`).

**f) Getrennte Worker-Instanz** – eine Instanz rechnet, eine antwortet:

```bash
# Worker
java -jar ao-app.jar --spring.profiles.active=worker --server.port=8081   # AO_INTERNAL_SECRET=…
# API
java -jar ao-app.jar --ao.ingest.scheduler-enabled=false --ao.semantic.scheduler-enabled=false
```

**g) Eigenes API-Präfix** (z. B. hinter einem Proxy unter `/ao`): Backend `AO_API_PREFIX=/ao`,
Frontend `VITE_AO_API_PREFIX=/ao`. Beide Werte müssen gleich sein.

### 3.5 Prüfen, ob alles verbunden ist

```bash
curl -s localhost:8080/api/health
# {"status":"ok","store":true,"vector_store":{"store":"qdrant","ok":true,…},"llm_configured":true}
curl -s localhost:8080/api/connections                    # Quellverbindungen (ohne Passwörter)
curl -s localhost:8080/api/datasources/dbs                # eingelesene Datenbanken
curl -s localhost:8080/api/semantic-compilation/status    # "calculated": true, sobald Terms existieren
curl -s localhost:6333/collections                        # bei Qdrant: beide Collections vorhanden
```

`"status":"degraded"` heißt: Store oder Vektorstore ist nicht erreichbar. `"llm_configured":false`
heißt: weder `DEFAULT_MODELS_*` noch rollenspezifische Endpunkte sind gesetzt.

### 3.6 Häufige Konfigurationsfehler

| Symptom | Ursache | Lösung |
|---|---|---|
| Start bricht ab: `… dimension … ao.vector.dimensions` | Embedding-Modell und Dimension passen nicht, oder alte Vektoren haben eine andere Größe | `ao.vector.dimensions` auf die Modellgröße setzen; Collections bzw. `data/` löschen |
| Start bricht ab: `ao.api-prefix must …` / `collides with …` | ungültiges oder kollidierendes Präfix | mit `/` beginnen, nicht mit `/` enden, nicht `/internal`, `/mcp`, `/actuator`, nicht unter `/api/v1` des Dashboards |
| Start bricht ab: `persistent H2 database with DATABASE_TO_LOWER=TRUE` | Datei-Store mit dieser Option | Option aus der URL entfernen und Store-Datei löschen |
| Gradle: Projekt `quasar-dashboard` nicht gefunden | Nachbarrepository fehlt | `C:\Projekte\Text2SQL\quasar-dashboard` auschecken |
| Log: `Structured LLM call … failed` immer wieder | Server versteht den Modus nicht | `structured-output` auf `json_object` oder `tool_calling` stellen |
| Log: `LLM role … is not configured` | Endpunkt oder Modell fehlt | `DEFAULT_MODELS_ENDPOINT` und `DEFAULT_MODELS_MODEL` setzen |
| LLM-Aufrufe gehen an `localhost:8888` | Fallback aus `ao-defaults.properties` greift | `DEFAULT_MODELS_*` immer explizit setzen |
| Keine Terms, Status bleibt `calculated: false` | Laufzeitschalter Kompilierung ist aus | `PUT /api/configurations/semantic-compilation {"enabled": true}` |
| Qdrant: `UNAVAILABLE` / Timeout | falscher Port (6333 statt 6334) oder Dienst nicht gestartet | gRPC-Port 6334 verwenden |
| Docker: Backend nicht erreichbar | im Container an `127.0.0.1` gebunden | `SERVER_ADDRESS=0.0.0.0` (im Image gesetzt) |
| Chat bricht nach 60 s ab | Proxy puffert SSE oder hat kurzen Timeout | Puffer aus, Read-Timeout ≥ 900 s |
| MCP-Tools melden Verbindungsfehler | Server bindet nicht an `localhost` | `ao.mcp.api-base-url` setzen |

---

## 4. Architektur im Überblick

### 4.1 Modulgraph

Die Pfeile zeigen Gradle-`api`-Abhängigkeiten. Jedes Modul sieht transitiv alle darunter liegenden.

```
                         ao-app            ao-dashboard-demo
                           │                  │        │
                           ▼                  ▼        ▼
                  ao-spring-boot-starter ◀────┘   ao-nl-chart ──▶ quasar-dashboard-backend
                       │         │                     │            (Composite Build)
                       ▼         ▼                     │
                    ao-api     ao-mcp ◀── (nur ao-common, HTTP zur eigenen API)
                       │◀──────────────────────────────┘
                       ▼
                    ao-agent
                       ▼
                    ao-semantic
                       ▼
                    ao-ingestion
              ┌────────┼──────────┬──────────────┐
              ▼        ▼          ▼              ▼
          ao-store  ao-vector   ao-llm      ao-connectors
              ▲        │          │              │
              └────────┘          ▼              ▼
                               ao-common ◀───────┘
```

Die Schichtung ist linear: Jedes Fachmodul darf nur auf Module darunter zugreifen. `ao-mcp` ist
bewusst entkoppelt und spricht ausschließlich per HTTP mit der öffentlichen API.

### 4.2 Schichten

| Schicht | Module | Verantwortung |
|---|---|---|
| Hosts | `ao-app`, `ao-dashboard-demo` | `@SpringBootApplication`, Profile, Demo-Daten |
| Bündelung | `ao-spring-boot-starter` | eine Abhängigkeit für alle Module außer NL-Charts |
| Schnittstellen | `ao-api`, `ao-mcp`, `ao-nl-chart` (Controller) | REST, SSE, MCP |
| Anwendungslogik | `ao-agent`, `ao-semantic`, `ao-ingestion`, `ao-nl-chart` (Service) | Agent, Kompilierung, Ingest, NL-Charts |
| Infrastruktur | `ao-store`, `ao-vector`, `ao-llm`, `ao-connectors` | Persistenz, Vektoren, LLM, Quell-DBs |
| Basis | `ao-common` | Konfiguration, Fehler, Uhr, Benutzer, Abbruch |

### 4.3 Laufzeitbild

```
HTTP ──▶ Controller (ao-api) ──▶ Service ──▶ Repository (ao-store) ──▶ Metadaten-Store
                                   │
                                   ├──▶ VectorStore (ao-vector) ──▶ Qdrant / In-Memory
                                   ├──▶ LlmGateway / Embedder (ao-llm) ──▶ OpenAI-kompatible Endpunkte
                                   └──▶ SourceDatabase (ao-connectors) ──▶ Quell-DBs (nur lesend)

Hintergrund:  IngestScheduler ──▶ IngestService ──▶ (Stages: PII, Regeln, Dataset-Sync)
              SemanticCompilationService ──▶ SemanticCompiler ──▶ (Regeln, PII-Propagation, Dataset-Sync)
```

### 4.4 Startreihenfolge

1. **Auto-Konfigurationen** in der Reihenfolge ihrer `after`-Angaben:
   `CommonAutoConfiguration` → `StoreAutoConfiguration` (vor Boots `LiquibaseAutoConfiguration`) →
   `VectorAutoConfiguration` / `LlmAutoConfiguration` → `IngestionAutoConfiguration` →
   `SemanticAutoConfiguration` → `AgentAutoConfiguration` → `ApiAutoConfiguration` →
   `McpAutoConfiguration`, `NlChartAutoConfiguration`.
2. `CommonAutoConfiguration` prüft beim Erzeugen die API-Präfixe; ein ungültiger Wert bricht hier ab.
3. `aoLiquibase` migriert den Store. `StoreJdbc` hängt von allen `SpringLiquibase`-Beans ab, damit
   kein Repository vor der Migration läuft.
4. `VectorAutoConfiguration` ruft `ensureCollections()` – bei Qdrant mit falscher Dimension bricht
   der Start hier ab.
5. Host-spezifische Beans laufen noch während der Kontexterzeugung (z. B. `DemoConfiguration`
   legt die Demo-DB an und setzt die Laufzeitschalter).
6. **`SmartLifecycle`** nach dem Webserver: `SemanticCompilationService` (Phase `MAX-200`) meldet
   sich beim Ingest-Scheduler an, dann startet `IngestScheduler` (Phase `MAX-100`) den ersten Ingest.
   Nach dem ersten Ingest stößt der Kompilierungs-Scheduler seinen ersten Lauf an.
7. `ApplicationReadyEvent`: der NL-Chart-Dataset-Sync läuft einmal (nur mit `ao-nl-chart`).

---

## 5. Querschnittskonzepte

Diese Muster ziehen sich durch alle Module. Wer sie kennt, liest den Code deutlich schneller.

### 5.1 Daten als `Map<String, Object>` statt Entitäten

Es gibt **kein JPA und keine Entitätsklassen**. Repositories liefern Zeilen als
`Map<String, Object>` mit **klein geschriebenen Spaltennamen** (`Rows.normalize`). Controller geben
diese Maps meist direkt als JSON zurück. Dadurch entsprechen die JSON-Felder 1:1 den Spaltennamen
des Originals (snake_case), ohne dass DTOs gepflegt werden müssen.

Folgen für die Arbeit am Code:

- Feldnamen sind Strings; ein Tippfehler fällt erst zur Laufzeit auf. Vorhandene Konstanten nutzen
  (`RuntimeSettings.VISUALIZATION`, `VectorStore.SEMANTIC_LAYER` …).
- `Timestamp`-Werte kommen als ISO-Instant-String an, `Clob` als String (`Rows.value`).
- Records gibt es dort, wo Typisierung wichtig ist: LLM-Ausgaben (`AgentModels`, `SemanticModels`),
  Konfiguration (`AoProperties`), Vektoren (`VectorStore.VectorRecord`), Konnektor-Ergebnisse
  (`TabularResult`).

### 5.2 Auto-Konfiguration und ersetzbare Beans

Jedes Fachmodul hat genau eine `*AutoConfiguration`, registriert über
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Beans heißen
`ao<Name>` und sind fast immer `@ConditionalOnMissingBean`. Ein Host ersetzt eine Komponente, indem
er eine eigene Bean desselben Typs definiert (in Tests mit `@Primary`). Die Fachklassen selbst
tragen **keine** Spring-Annotationen (außer Controllern) – sie werden im Konstruktor verdrahtet und
lassen sich ohne Spring testen.

### 5.3 Konfiguration

Alle Einstellungen hängen an einem Record-Baum `AoProperties` (`ao.*`). Umgebungsvariablen des
Originals werden in `ao-defaults.properties` gemappt. Instanzweite **Laufzeitschalter**
(Visualisierung, PII, Kompilierung, SQL-Timeout, Distinct-Proben) liegen dagegen in der Tabelle
`app_configurations` und werden über `RuntimeSettings` gelesen – sie ändern sich ohne Neustart über
die API. Für Einsteiger erklärt in Abschnitt 3; Details: [`ao-common`](module/ao-common.md),
[`ao-store`](module/ao-store.md).

### 5.4 Fester Benutzer

Es gibt keine Anmeldung. `CurrentUser` liefert eine konfigurierte ID (`ao.user.id`), die überall
geschrieben wird, wo das Original eine Benutzer-ID speichert. Eine spätere Anmeldung würde genau
diese Bean ersetzen. Es gibt keine 401/403-Antworten und keine Spring-Security-Abhängigkeit.

### 5.5 Eine Uhr

`AoTime` kapselt die gemeinsame `Clock`. Reihenfolge: `ao.clock.fixed-now` → eine vorhandene
`Clock`-Bean (z. B. `quasarDashboardClock`) → System-UTC. Ein Host kann auch eine eigene
`AoTime`-Bean definieren. **Nie** `Instant.now()` oder `LocalDate.now()` direkt verwenden, sonst
rechnen Demo, Tests und Prompts („heute ist …“) unterschiedlich.

```java
Instant now = time.now();          // statt Instant.now()
LocalDate today = time.today();    // statt LocalDate.now(); immer UTC
```

### 5.6 Fehlerformate

`ApiException` trägt Status und **Stil**: `DETAIL` (`{"detail": …}`, Routen, die im Original FastAPI
bediente), `ERROR` (`{"error": …}`, Next.js-Routen) und `PLAIN` (Klartext). `ApiExceptionHandler`
rendert sie nur für Controller unter `de.jentsch.autoontology`, damit Fehlerbehandlungen des Hosts
unberührt bleiben. Validierungsfehler werden zu FastAPI-artigen 422-Antworten.

### 5.7 Kooperativer Abbruch

`CancellationToken` ist ein Flag plus Abbruch-Hooks. Ein Lauf bindet sein Token per `bind()` an den
Thread (`ThreadLocal`). `JdbcSourceDatabase.execute` registriert dann automatisch
`Statement.cancel()` als Hook. `AgentGraph` prüft das Token vor jedem Knoten, der Compiler vor jeder
Tabelle. Es werden nie Threads hart beendet. Ein Abbruch erreicht den Aufrufer immer als
`CancelledException` – auch wenn er mitten in einem Agent-Knoten (während der SQL-Ausführung)
passiert.

### 5.8 Nebenläufigkeit

- Virtual Threads überall: Chat-Läufe (`Thread.ofVirtual()`), Scheduler-Executors, Tabellen der
  Kompilierung (`newVirtualThreadPerTaskExecutor`).
- LLM-Aufrufe sind prozessweit über ein `Semaphore` in `LangChain4jLlmGateway` begrenzt
  (`ao.llm.max-inflight`). Die Kompilierung begrenzt zusätzlich die parallelen Tabellen
  (`ao.semantic.llm-max-inflight`).
- Ingest-Läufe sind über ein `ReentrantLock` serialisiert, Kompilierungsläufe über ein
  `AtomicBoolean`, Entity-Coverage-Läufe ebenfalls.
- Je Konversation ist genau ein Chat-Lauf aktiv (`ChatService.runs`); Events eines Laufs werden
  unter dem Monitor des Laufs gepuffert und versendet, damit `watch` nichts verpasst.
- **Scheduler planen nie doppelt.** `IngestScheduler` und `SemanticCompilationService` halten genau
  eine geplante Aufgabe. Ein `trigger()` während eines Laufs wird vermerkt und direkt danach
  ausgeführt.

### 5.9 Prompts

Prompts liegen als Ressourcen `prompts/<name>.txt` (in `ao-agent` und `ao-semantic`), wörtlich aus
dem Original exportiert. Kopfzeilen mit `#!` nennen die Quelle und werden beim Laden entfernt.
Platzhalter: `{{name}}`. Laden und Füllen: `Prompts.get(name)` / `Prompts.render(name, map)`.
Gefüllt wird in einem Durchgang: Eingesetzte Werte (Benutzerfragen, Beispielwerte) werden nicht
erneut nach Platzhaltern durchsucht. Einige Prompts sind im Java-Code zusammengesetzt
(`AgentPrompts`, `ChartAdvisor`, `NlChartService.systemPrompt`).

Was in einen Prompt darf, ist geregelt (NFR-SEC-04): keine Beispielwerte von PII-Spalten, solange
`ao.security.pii-samples-to-llm=false`, und keine Werte, die wie Anweisungen aussehen
(`Nodes.instructionLike`). Die PII-Spalten holt man einmal mit `PiiDetection.piiColumnIds()`.

### 5.10 Strukturierte LLM-Ausgabe

Jede LLM-Antwort, die Daten liefert, ist ein **Record** mit `@LlmField`-Beschreibungen.
`OutputSchema` erzeugt daraus das JSON-Schema (`additionalProperties: false`, alle Felder
`required`), `StructuredParser` parst strikt (unbekannte Felder schlagen fehl) und ruft optional
`LlmOutput.validate()` auf. Feldnamen und -reihenfolge stammen aus den Pydantic-Modellen des
Originals – die Prompts verweisen darauf. **Feldnamen nie umbenennen**, ohne den Prompt anzupassen.

**Antworten defensiv lesen.** „`required`“ im Schema ist eine Bitte an das Modell, keine Garantie:
Fehlt ein Feld, steht im Record `null`. Listen deshalb vor dem Iterieren, Texte vor `strip()`
prüfen – sonst wird aus einer schwachen Antwort eine `NullPointerException` und aus dem
vorgesehenen Fallback ein abgebrochener Lauf.

```java
List<String> entities = m.required_entity_name() == null ? List.of() : m.required_entity_name();
```

### 5.11 Logging

Logmuster `%5p [run=%X{ao.run:-} node=%X{ao.node:-}]`: `AgentRunner` setzt eine kurze Run-ID,
`AgentGraph` den Knotennamen in den MDC. Credentials werden nie geloggt
(`ConnectionSpec.redact`, `toString()` der Konfigurations-Records blendet Keys aus).

### 5.12 Transaktionen

Grundsätzlich Auto-Commit je Statement. Ausnahme: `CatalogWriter` schreibt den Katalog-Diff einer
Datenbank in einer `TransactionTemplate`. Zwischen Store und Vektorstore gibt es keine gemeinsame
Transaktion; Reihenfolge ist immer „erst Store, dann Vektoren“.

---

## 6. Zentrale Abläufe

### 6.1 Ingest (Katalog → Store → `data_objects_layer`)

```
IngestScheduler.runOnce
 └─ IngestService.runAll                        (je Verbindung, Fehler isoliert)
     └─ doRun(spec)                              (Lock: serialisiert)
         ├─ ConnectionService.connector(spec)    → SourceDatabase
         ├─ CatalogExtractor.extract(db, filter) → Catalog (Tabellen, Spalten, FKs, Historie)
         ├─ CatalogWriter.write                  → Diff + Upsert in einer Transaktion; ColumnAttributes
         │                                         entfernter Tabellen/Spalten werden mitgelöscht
         ├─ vectors.deleteByIds(entfernte Objekte) → data_objects_layer und semantic_layer
         ├─ writeQueryHistory                    → QueryHistoryParser (JSqlParser), sql_query, column__join
         ├─ Stage.afterCatalogWrite              → PiiDetection
         ├─ DataLayerEmbedder.embedDatabase      → Embedding in Chunks, alter Bestand erst vor dem 1. Chunk gelöscht
         └─ Stage.afterIngest                    → Regel-Replay (Kompilierung aus), Dataset-Sync (NL-Modul)
```

Details: [`ao-ingestion`](module/ao-ingestion.md), [`ao-connectors`](module/ao-connectors.md).

### 6.2 Kompilierung (Katalog → Ontologie → `semantic_layer`)

```
SemanticCompilationService.runNow             (nur wenn semantic_compilation_enabled)
 └─ je Datenbank: SemanticCompiler.compile
     ├─ Stufe 1 stageTaxonomy   parallel je Tabelle: Profiler → FK-Vorschläge → Attribut-Specs → Terms (LLM)
     │                          Commit in kanonischer Reihenfolge, Embedding je Term
     ├─ Stufe 2 stageSemanticFks  Fast Path über FKs, sonst Vektorsuche + LLM; Pruning; Join-Defaults
     ├─ Stufe 3 stageSqlAttributes  Ausdrücke aus der Query-Historie, Top 10 per LLM
     └─ Stufe 4 stageBridges      reine FK-Tabellen → Join-SQL per LLM → Ziel-Term per Rerank
 └─ danach: Regel-Replay, PII-Propagation, Hooks (Dataset-Sync)
```

Details: [`ao-semantic`](module/ao-semantic.md).

### 6.3 Chat-Frage (SSE)

```
POST /api/chat/completions ─▶ ChatController ─▶ ChatService.completions
   ├─ 409-Prüfung, Run anlegen, SseEmitter abonnieren, Ping alle 15 s
   └─ Virtual Thread: execute
        ├─ Konversation anlegen/laden, User-Nachricht + Analytics speichern
        ├─ AgentRunner.run ─▶ AgentGraph.run (Knoten senden step/sql-Events)
        │     question_intent → question_extraction → retrieve_candidates → prepare_candidates
        │     → construct_sql → validate_sql_query ⇄ reconstruct_sql → execute_sql_query
        │     → check_empty_like_result → check_value_repair → format_and_respond
        ├─ result-Event, Assistenten-Nachricht speichern
        ├─ (mit conversation_id) ChartAdvisor → charts-Event, zweite Nachricht
        └─ [DONE]
```

Details: [`ao-agent`](module/ao-agent.md), [`ao-api`](module/ao-api.md).

### 6.4 NL-Chart

```
POST /api/nl-chart/suggest ─▶ NlChartService.suggest
   ├─ Kandidaten-Datasets (Vektorsuche label=Dataset, sonst alle)
   ├─ Modus A: LLM → ChartSuggestion → ChartValidator (Katalog, Spalten, buildQuery-Port, Dashboard-Engine)
   │           bis zu 2 Reparaturrunden; danach error-Event und Ende
   └─ Modus B (nur wenn kein Dataset gewählt wurde): AgentRunner → Zeilen → ChartAdvisor → QueryResult
```

Details: [`ao-nl-chart`](module/ao-nl-chart.md).

---

## 7. Wo finde ich was?

| Ich will … | Modul | Einstieg |
|---|---|---|
| eine neue Einstellung `ao.*` | `ao-common` | `AoProperties`, ggf. `ao-defaults.properties` |
| einen neuen Laufzeitschalter (über API änderbar) | `ao-store`, `ao-api` | `RuntimeSettings`, `AppController` (Konfigurationsrouten) |
| eine Tabelle oder Spalte im Store ergänzen | `ao-store` | neues Changeset in `db/changelog/ao/001-schema.sql` (oder neue Datei im Changelog) |
| eine neue Quell-Datenbank-Engine | `ao-connectors` | `JdbcSourceDatabase`, `Connectors`, `ConnectorContract` |
| etwas am Katalog-Ingest ändern | `ao-ingestion` | `IngestService`, `CatalogExtractor`, `CatalogWriter` |
| einen Schritt nach dem Ingest einhängen | `ao-ingestion` | `IngestService.Stage` |
| die Embedding-Texte ändern | `ao-ingestion`, `ao-semantic` | `EmbeddingTexts`, `SemanticEmbedder` (Retrieval-Vertrag!) |
| die Ontologie-Erzeugung beeinflussen | `ao-semantic` | `SemanticCompiler`, `TableTaxonomy`, `Profiler` |
| einen Agent-Knoten ändern oder ergänzen | `ao-agent` | `Nodes`, `AgentRunner.buildGraph`, `NodeLabels` |
| die SQL-Prüfung erweitern | `ao-agent` | `SqlStaticChecks`, `Nodes.validateSql` |
| Dialektregeln für das LLM | `ao-agent` | `prompts/dialect_<name>.txt`, `AgentPrompts.dialectRules` |
| einen LLM-Anbieter anbinden | `ao-llm` | `LlmGateway`, `LangChain4jLlmGateway` |
| einen neuen REST-Endpunkt | `ao-api` | passender Controller, `@RequestMapping("${ao.api-prefix:/api}")` |
| ein MCP-Tool ergänzen | `ao-mcp` | `McpTools.TOOLS`, `McpTools.call` |
| Chart-Vorschläge verbessern | `ao-nl-chart`, `ao-agent` | `NlChartService.systemPrompt`, `ChartValidator`, `ChartAdvisor` |
| Demo-Daten ändern | `ao-app` | `DemoDatabase`, `demo/questions.yaml` |

---

## 8. Konventionen

- **Java 21**, Records für Werte, `var` sparsam, keine Lombok.
- **Compiler** mit `-parameters -Xlint:all,-processing,-serial -Werror`: Jede Warnung bricht den
  Build. `@SuppressWarnings("unchecked")` ist bei Map-Casts üblich, aber nur am kleinsten Element.
- **SPDX-Header** `// SPDX-License-Identifier: Apache-2.0` in jeder neuen Datei. Aus dem Original
  übernommene Teile tragen „Derived from NVIDIA Auto-Ontology“ (siehe `NOTICE`).
- **Javadoc mit Anforderungs-ID** (`FR-…`, `NFR-…`) und, wo portiert, mit der Originalquelle
  (`catalog/extract.py::…`). Bitte beibehalten – es ist die wichtigste Navigationshilfe.
- **Bean-Namen** `ao<Typ>`, immer `@ConditionalOnMissingBean`.
- **Keine Spring-Annotationen in Fachklassen**; Verdrahtung nur in der Auto-Konfiguration.
- **API-Präfix** nie hart kodieren: `@RequestMapping("${ao.api-prefix:/api}")`.
- **Uhr** nur über `AoTime`, **IDs** über `Ids.newId()`, **JSON** über `Json` (Jackson 3,
  `tools.jackson.*`; nicht `com.fasterxml.jackson.databind`).
- **Tests** mit JUnit 5 und AssertJ; Docker-basierte Tests mit `@Tag("integration")`, Dateiname
  `*IT.java`. Standard-Build ohne Docker (H2 und eingebettetes PostgreSQL).
- **LLM in Tests** immer über `ScriptedLlmGateway`, nie gegen einen echten Endpunkt.

---

## 9. Fallstricke und bekannte technische Schulden

Gesammelt aus allen Modulen; Details stehen jeweils im Moduldokument.

| # | Thema | Beschreibung | Modul |
|---|---|---|---|
| 1 | **API-Key im Repository** | `ao-defaults.properties` enthält Fallbacks für `DEFAULT_MODELS_ENDPOINT/MODEL/API_KEY` inklusive eines echten Keys. Ohne gesetzte Umgebungsvariablen wird er benutzt; der Konfigurationsbericht loggt dessen Anfang. Sollte durch leere Defaults ersetzt werden. | `ao-common` |
| 2 | LLM-Metriken nicht an Micrometer angebunden | `LlmMetrics` zählt intern; `setListener` wird nirgends aufgerufen. Unter `/actuator/metrics` erscheinen keine LLM-Zähler. | `ao-llm` |
| 3 | Intent-Validierung „fail-open“ | Liefert das LLM keine gültige Antwort, gilt das SQL nach der statischen Prüfung als gültig. | `ao-agent` |
| 4 | `prediction: true` | Es gibt keinen Vorhersagezweig; die Frage läuft trotzdem durch den SQL-Pfad statt mit Fehler abzubrechen (FR-AGT-01). | `ao-agent` |
| 5 | Abbruch laufender LLM-Aufrufe | Das Token bricht JDBC-Statements ab und wird zwischen Knoten geprüft; ein laufender HTTP-Aufruf zum LLM läuft bis zum Timeout weiter. | `ao-agent`, `ao-llm` |
| 6 | T-SQL-Limit | `SqlServerSource.limit` umschließt das SQL mit `SELECT TOP n * FROM (…)`. Enthält das innere SQL ein `ORDER BY` ohne `TOP`/`OFFSET`, lehnt SQL Server das ab. `limit()` nur um eigenes SQL ohne `ORDER BY` legen; Agent-SQL wird über `setMaxRows` begrenzt. | `ao-connectors` |
| 7 | Credentials im Klartext | UI-Verbindungen speichern Passwörter als JSON in `catalog_database.connection` (wie das Original ohne Vault). Die API gibt sie nie zurück. | `ao-ingestion` |
| 8 | Dynamisches SQL in `patch*` | `CatalogRepository.patchNode` und `SemanticRepository.patch` bauen `SET`-Klauseln aus Map-Schlüsseln. Aufrufer müssen die Schlüssel whitelisten (die Controller tun das). | `ao-store` |
| 9 | Leere IN-Listen | `StoreJdbc` ersetzt leere Collections durch `["__none__"]`, damit `IN (:ids)` gültig bleibt. | `ao-store` |
| 10 | Reconcile nur für den Data Layer | `/internal/vector/reconcile` gleicht nur `data_objects_layer` ab und läuft nicht automatisch; Neuaufbau in neue Collections mit Alias-Umschaltung (FR-VEC-06) fehlt. | `ao-api`, `ao-vector` |
| 11 | Verbindungs-Präzedenz | Ingest nimmt konfigurierte Verbindungen zuerst, Kompilierung und Chat UI-Verbindungen zuerst. Gibt es beide, werden ggf. unterschiedliche Datenbanken ingestet und kompiliert. | `ao-ingestion`, `ao-semantic` |
| 12 | NL-Chart-Grenzen | `conversation_id` wird nur für Analytics verwendet; Zeit- und Aufrufbudget (NFR-NLC-02) fehlt; ohne Vektortreffer sind **alle** Datasets Kandidaten, Modus B läuft nur, wenn das LLM kein Dataset wählt. Gleichnamige Metriken mehrerer Datasets werden als `<Name> (<Tabelle>)` gespiegelt. | `ao-nl-chart` |
| 13 | MCP über `localhost` | Ohne `ao.mcp.api-base-url` ruft der MCP-Server `http://localhost:<port><prefix>`. Bindet der Server an eine andere Adresse, muss die URL gesetzt werden. | `ao-mcp` |
| 14 | Arbeitsverzeichnis | `DomainContext` liest `.semantic_summaries/<db>.json` und das Demo-Profil schreibt `data/` relativ zum Arbeitsverzeichnis. | `ao-semantic`, `ao-app` |
| 15 | Persistenter H2-Store | `DATABASE_TO_LOWER=TRUE` ist für Datei-Stores verboten (Liquibase). In-Memory ist erlaubt. | `ao-store` |
| 16 | Terms sind datenbankübergreifend | Merge-Schlüssel ist (`name`, `source`). Zwei Datenbanken mit einer Tabelle `CUSTOMERS` teilen sich den Term „Customer“. Der Reset bzw. das Löschen einer Datenbank lässt geteilte Terms deshalb stehen. | `ao-store`, `ao-semantic` |
| 17 | Read-only ist eine Syntaxprüfung | `ReadOnlySql` erkennt schreibende Statements (auch in CTEs) und Sperren, nicht aber Funktionen mit Seiteneffekten. Der Lesebenutzer bleibt Pflicht. | `ao-connectors` |
| 18 | Untypisierte Request-Bodies | Controller nehmen `Map<String, Object>`; nichts validiert automatisch. Jede neue Route muss Pflichtfelder, Typen und Eindeutigkeit selbst prüfen, sonst wird aus einer falschen Eingabe ein 500 der Datenbank. | `ao-api` |
| 19 | `"null"` als Text | `String.valueOf(map.getOrDefault(key, ""))` ergibt bei vorhandenem Schlüssel mit Wert `null` den Text `"null"`. `Rows.strOr` bzw. `AppController.str/text` verwenden. | alle |
| 20 | Re-Embedding nach dem Speichern | Routen, die erst speichern und dann neu einbetten, antworten bei einem Embedding-Fehler mit 500, obwohl die Änderung im Store steht. | `ao-api` |

---

## 10. Moduldokumente (Übersicht)

| Modul | Dokument |
|---|---|
| `ao-common` | [module/ao-common.md](module/ao-common.md) |
| `ao-store` | [module/ao-store.md](module/ao-store.md) |
| `ao-vector` | [module/ao-vector.md](module/ao-vector.md) |
| `ao-llm` | [module/ao-llm.md](module/ao-llm.md) |
| `ao-connectors` | [module/ao-connectors.md](module/ao-connectors.md) |
| `ao-ingestion` | [module/ao-ingestion.md](module/ao-ingestion.md) |
| `ao-semantic` | [module/ao-semantic.md](module/ao-semantic.md) |
| `ao-agent` | [module/ao-agent.md](module/ao-agent.md) |
| `ao-api` | [module/ao-api.md](module/ao-api.md) |
| `ao-mcp` | [module/ao-mcp.md](module/ao-mcp.md) |
| `ao-nl-chart` | [module/ao-nl-chart.md](module/ao-nl-chart.md) |
| `ao-spring-boot-starter` | [module/ao-spring-boot-starter.md](module/ao-spring-boot-starter.md) |
| `ao-app` | [module/ao-app.md](module/ao-app.md) |
| `ao-dashboard-demo` | [module/ao-dashboard-demo.md](module/ao-dashboard-demo.md) |

Jedes Moduldokument hat denselben Aufbau: Zweck · Abhängigkeiten · Klassen · Beans · Abläufe ·
Konfiguration · Erweiterungspunkte · Code-Beispiele · Tests · Fallstricke · Anforderungsbezug.

---

## 11. Kochbuch

Dieser Abschnitt zeigt eine Änderung **durch alle Schichten** und verweist danach auf die
Beispiele der einzelnen Module. Alle Beispiele folgen den Konventionen aus Abschnitt 8.

### 11.1 Ein Feature von der Einstellung bis zum Test

Aufgabe: Terms sollen eine Prüfnotiz bekommen („reviewed“), abrufbar und setzbar über die API. Die
maximale Länge der Notiz ist konfigurierbar.

**1. Einstellung** (`ao-common`)

```java
// AoProperties.java – im Record Semantic
public record Semantic(
        @DefaultValue("true") boolean rankSqlByUsage,
        @DefaultValue("6") int llmMaxInflight,
        @DefaultValue("24") int intervalHours,
        @DefaultValue("true") boolean schedulerEnabled,
        @DefaultValue("500") int reviewNoteMaxLength) {}        // ao.semantic.review-note-max-length
```

**2. Schema** (`ao-store`) – ein neues Changeset, bestehende nie ändern

```sql
--changeset ao:008-term-review
ALTER TABLE term ADD COLUMN reviewed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE term ADD COLUMN review_note ${text};
```

**3. Repository** (`ao-store`) – SQL, das auf PostgreSQL und H2 läuft

```java
// SemanticRepository.java
public boolean markReviewed(String termId, String note, Instant now) {
    Map<String, Object> p = new HashMap<>();                    // HashMap: Werte dürfen null sein
    p.put("id", termId);
    p.put("note", note);
    p.put("now", now);
    return db.update("UPDATE term SET reviewed_at = :now, review_note = :note WHERE id = :id", p) == 1;
}
```

`TERM_COLS` um `t.reviewed_at, t.review_note` ergänzen, damit die Felder in allen Term-Antworten
erscheinen.

**4. Endpunkt** (`ao-api`) – prüfen, bevor geschrieben wird

```java
// SemanticController.java
@PutMapping("/terms/{termId}/review")
public Map<String, Object> review(@PathVariable String termId, @RequestBody Map<String, Object> body) {
    term(termId);                                               // 404 bei ungültiger oder unbekannter ID
    // props: AoProperties, wie jede Abhängigkeit per Konstruktor in den Controller gereicht
    String note = AppController.str(body.get("note"));          // "" und null → null
    if (note != null && note.length() > props.semantic().reviewNoteMaxLength()) {
        throw ApiException.unprocessable("note is longer than " + props.semantic().reviewNoteMaxLength() + " characters");
    }
    if (!semantic.markReviewed(termId, note, time.now())) {
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The change could not be stored");
    }
    return Map.of("data", term(termId));
}
```

Die Route ist neu gegenüber dem Original, gehört also in die Allowlist von `OpenApiContractTest`.

**5. Tests** – Repository gegen beide Stores, Endpunkt über den laufenden Host

```java
// StoreSchemaTest.java
@ParameterizedTest
@MethodSource("stores")
void termsCanBeReviewed(DataSource ds) throws Exception {
    SemanticRepository semantic = new SemanticRepository(migrate(ds));
    Instant now = Instant.parse("2026-06-30T00:00:00Z");
    String id = semantic.mergeTerm("Customer " + System.nanoTime(), null, List.of(), "semantic", now);

    assertThat(semantic.markReviewed(id, "checked with sales", now)).isTrue();
    assertThat(semantic.termById(id).orElseThrow()).containsEntry("review_note", "checked with sales");
    assertThat(semantic.markReviewed("00000000-0000-0000-0000-000000000000", null, now)).isFalse();
}
```

```java
// in einem @SpringBootTest(webEnvironment = RANDOM_PORT) wie DemoEndToEndTest
HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/terms/" + id + "/review"))
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString("{\"note\":\"ok\"}")).build(), HttpResponse.BodyHandlers.ofString());
assertThat(r.statusCode()).isEqualTo(200);
assertThat(r.body()).contains("\"review_note\":\"ok\"");
```

**6. Frontend** – ein Aufruf über den Client, Text in beiden Sprachdateien

```ts
await ao.put(`/terms/${id}/review`, { note: note.value })
```

**7. Dokumentation** – Route in `module/ao-api.md` (Sonderfälle), Einstellung in
`DOKUMENTATION.md` Abschnitt 10, Changeset in `module/ao-store.md`.

### 11.2 Checkliste vor dem Commit

- `./gradlew build` läuft durch (`-Werror`: jede Warnung ist ein Fehler).
- Neue Logik hat einen Test, der **ohne** LLM und ohne Docker läuft.
- Requests: Pflichtfelder, Typen und Eindeutigkeit geprüft → 4xx statt Datenbankfehler; `skip`/`limit`
  begrenzt.
- LLM-Antworten: Listen und Texte auf `null` geprüft; es gibt einen Fallback.
- Zeit über `AoTime`, IDs über `Ids.newId()`, JSON über `Json`; kein hart kodiertes `/api`.
- SQL für den Store läuft auf PostgreSQL **und** H2; Werte aus Requests nur als benannte Parameter.
- SQL für Quell-Datenbanken: Bezeichner über `quote`/`qualify`, Limit über `limit()`, vor dem
  Ausführen `ReadOnlySql.violation`.
- Prompts: keine Beispielwerte von PII-Spalten, keine anweisungsartigen Werte.
- Frontend: `pnpm test && pnpm typecheck && pnpm --filter ao-demo typecheck`.

### 11.3 Wo die übrigen Beispiele stehen

| Thema | Dokument, Abschnitt „Code-Beispiele“ |
|---|---|
| Einstellung ergänzen, `ApiException`-Formen, abbrechbarer Lauf, `Rows`/`Json` | [`ao-common`](module/ao-common.md) |
| Changeset, Repository-Methoden, IN-Listen, Laufzeitschalter, Repository-Test | [`ao-store`](module/ao-store.md) |
| Vektoren schreiben, suchen, löschen; Vertragstest | [`ao-vector`](module/ao-vector.md) |
| Strukturierter LLM-Aufruf mit Record, Embeddings, Rollen, `ScriptedLlmGateway` | [`ao-llm`](module/ao-llm.md) |
| Konnektor öffnen, Read-only-Prüfung, dialektneutrales SQL, neue Engine | [`ao-connectors`](module/ao-connectors.md) |
| Ingest-Stage, Verbindungen und Filter, Query-Historie, PII, Suche | [`ao-ingestion`](module/ao-ingestion.md) |
| Kompilierung steuern, Hooks, LLM-Schritte testen, Embedding-Zeilen | [`ao-semantic`](module/ao-semantic.md) |
| Agent aufrufen, Knoten ergänzen, statische SQL-Prüfung, Join-Pfade | [`ao-agent`](module/ao-agent.md) |
| Neuer Endpunkt, `curl`-Aufrufe mit Fehlerformen, SSE senden | [`ao-api`](module/ao-api.md) |
| Neues MCP-Tool, Tools direkt und über das Protokoll aufrufen | [`ao-mcp`](module/ao-mcp.md) |
| NL-Chart-Stream, Validierung, `QueryContextBuilder`, `SqlResults`, Sync | [`ao-nl-chart`](module/ao-nl-chart.md) |
| Minimaler Host, Bean ersetzen, Schema selbst migrieren | [`ao-spring-boot-starter`](module/ao-spring-boot-starter.md) |
| Seite mit Fehleranzeige, API-Client, Stream lesen, Ergebnisse rendern | [`frontend/quasar-ontology`](frontend/quasar-ontology.md) |
| NL-Plugin einbinden, Client ohne Komponente | [`frontend/quasar-dashboard-nl`](frontend/quasar-dashboard-nl.md) |
