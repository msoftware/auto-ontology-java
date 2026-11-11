# Modul `ao-app`

Paket `de.jentsch.autoontology.app` · [Zurück zur Übersicht](../README.md)

## Zweck

Der **eigenständige Host** von Auto-Ontology: eine `@SpringBootApplication` über dem Starter, mit
Actuator, den Profilen `default`, `demo`, `qdrant` und `worker` und der Demo-Datenbank „Sales“.
Außerdem liegen hier die Ende-zu-Ende-, Vertrags-, Präfix- und MCP-Tests des Gesamtsystems.

## Abhängigkeiten

`implementation`: `ao-spring-boot-starter`, `spring-boot-starter-actuator`.
`runtimeOnly`: H2, PostgreSQL-Treiber. Tests: `spring-boot-starter-webmvc-test`, eingebettetes
PostgreSQL. Plugin `org.springframework.boot` (ausführbares JAR per `bootJar`).

`bootRun` läuft mit dem **Projektverzeichnis `auto-ontology-java`** als Arbeitsverzeichnis
(`workingDir = rootProject.projectDir.parentFile`), damit `data/` und `.semantic_summaries/` dort
landen.

## Klassen

| Klasse | Aufgabe |
|---|---|
| `AoApplication` | Main-Klasse |
| `demo.DemoConfiguration` | nur Profil `demo`: Demo-DB anlegen, Laufzeitschalter seeden, `DemoController` |
| `demo.DemoConfiguration.DemoController` | `GET {prefix}/demo/questions`, `POST {prefix}/demo/reingest` |
| `demo.DemoDatabase` | erzeugt und befüllt die H2-Quell-DB „Sales“ deterministisch |

## Profile

| Profil | Datei | Inhalt |
|---|---|---|
| default | `application.yml` | Bind `127.0.0.1:8080`, Graceful Shutdown, Virtual Threads, PostgreSQL-Store aus `POSTGRES_*`, MVC-Async-Timeout 1800 s, Uploads bis 50 MB, `ao.api-prefix` aus `AO_API_PREFIX`, Qdrant, Actuator (`health,info,metrics,prometheus`, Probes), springdoc unter `/v3/api-docs`, Logmuster mit `run`/`node` |
| `demo` | `application-demo.yml` | H2-Datei-Store `./data/ao-store` (`MODE=PostgreSQL`), H2-Konsole `/h2-console` nur lokal, `ao.demo=true`, Benutzer `demo`, `fixed-now 2026-06-30` (`AO_FIXED_NOW`), In-Memory-Vektorstore mit Persistenz, Dimension 768, Hash-Embedding, Verbindung `demo_sales` (`jdbc:h2:mem:demo_sales`, Deny-Regex `^QUERY_HISTORY$`) |
| `qdrant` | `application-qdrant.yml` | Qdrant statt In-Memory (`QDRANT_HOST`, `QDRANT_PORT`), keine Persistenz in `vector_record` |
| `worker` | `application-worker.yml` | Scheduler an, `ao.internal.secret` aus `AO_INTERNAL_SECRET` |

Profile lassen sich kombinieren, z. B. `demo,qdrant`.

## Demo-Datenbank

`DemoConfiguration.aoDemoReady` läuft während der Kontexterzeugung, also **vor** dem ersten Ingest:

1. Für jede konfigurierte Verbindung mit `jdbc:h2:mem:demo_sales…` wird `DemoDatabase.create(url)`
   aufgerufen (einmal je JVM; existiert das Schema schon, passiert nichts).
2. `semantic_compilation_enabled`, `visualization_enabled`, `pii_detection_enabled` werden per
   `seed` auf `true` gesetzt, falls noch kein Wert gespeichert ist.

`DemoDatabase` erzeugt mit festem Seed (42) ein Vertriebsschema für den Zeitraum 2024-07-01 bis
2026-06-30:

| Schema | Tabellen |
|---|---|
| `SALES` | `CUSTOMERS`, `ORDERS`, `ORDER_LINES`, `INVOICES`, `INVOICE_LINES`, `ITEM_SUPPLIER_LINK` |
| `APPLICATION` | `CITIES`, `STATE_PROVINCES`, `PEOPLE` |
| `WAREHOUSE` | `STOCK_ITEMS`, `COLORS` |
| `PURCHASING` | `SUPPLIERS` |
| `DEMO_META` | `QUERY_HISTORY` (vom Katalog ausgeschlossen) |

Eingebaute Demo-Fälle: fehlender FK `SALES.INVOICES.CUSTOMER_ID`, Text-Datum
`SALES.ORDERS.LEGACY_ORDER_DATE` (`YYYYMMDD`), Kategorie `'Wholesale'` für die Wertreparatur,
Kommentare an einem Teil der Objekte, reine FK-Tabelle `ITEM_SUPPLIER_LINK` (Bridge), PII-Spalten
`EMAIL`/`PHONE`, eine Query-Historie mit `END_TIME` in den letzten 24 Stunden.

Die erwarteten Antworten der Demo-Fragen stehen in `demo/questions.yaml`. Wer `DemoDatabase`
ändert, muss die Werte dort neu berechnen.

## Starten

```bash
cd backend
./gradlew :ao-app:bootRun --args='--spring.profiles.active=demo'
./gradlew :ao-app:bootJar        # build/libs/ao-app-0.1.0-SNAPSHOT.jar (Basis des Docker-Images)
```

Docker: `docker/backend.Dockerfile` kopiert das JAR in ein `eclipse-temurin:21-jre`-Image mit
eigenem Benutzer und `SERVER_ADDRESS=0.0.0.0`.

## Tests

| Test | Profil / Setup | Prüft |
|---|---|---|
| `DemoEndToEndTest` | `demo`, In-Memory-Store mit `DATABASE_TO_LOWER=TRUE`, Dimension 256, `ScriptedLlmGateway` als `@Primary` | wartet, bis die Kompilierung ≥ 12 Terms erzeugt hat; ein Chat-Lauf repariert ein SQL mit falscher Tabelle (Rekonstruktion im Trace) und korrigiert `'wholesale'` über `check_value_repair` |
| `OpenApiContractTest` | Standard | `/v3/api-docs` gegen die OpenAPI-Referenz des Originals, mit Allowlist |
| `CustomApiPrefixTest` | `ao.api-prefix=/ao` | alle Endpunkte unter `/ao/…`, `/api/…` = 404; ungültige Präfixe brechen den Start ab |
| `McpServerTest` | Standard, Zufallsport | MCP-Initialisierung, `tools/list` mit allen 9 Tools, `check_readiness` meldet „nicht bereit“ mit Blockern |

## Erweiterungspunkte

- **Neue Demo-Frage:** in `DemoController.questions()` und mit erwartetem Wert in
  `demo/questions.yaml`.
- **Neues Profil:** `application-<name>.yml` in `src/main/resources`.
- **Zusätzliche Host-Beans:** in `AoApplication` oder neuen `@Configuration`-Klassen im Paket
  `de.jentsch.autoontology.app` (Komponentenscan).

## Fallstricke

- `DEFAULT_MODELS_*` setzen; sonst greifen die Fallbacks aus `ao-defaults.properties` (siehe
  [`ao-common`](ao-common.md)).
- Der Demo-Store liegt in `data/ao-store.mv.db`. Ein Wechsel von `ao.vector.dimensions` oder des
  Embedding-Providers bricht den Start ab, bis `data/` gelöscht ist (gespeicherte Vektoren haben die
  alte Dimension).
- `POST /api/demo/reset` (FR-DEMO-07) fehlt; `reingest` liest nur neu ein.
- Die Demo-Quell-DB ist eine In-Memory-H2, die Hikari mit `readOnly = true` öffnet; sie wird bei
  jedem JVM-Start neu erzeugt, während der Store persistent bleibt. Das ist gewollt (stabile IDs über
  den Diff), kann aber verwirren, wenn man Daten in der Quell-DB von Hand ändert.

## Anforderungsbezug

Abschnitt 19 (FR-DEMO-01 bis FR-DEMO-05), NFR-SEC-06, NFR-DEP-01, NFR-OBS-01/02, NFR-TST-03/04/07,
FR-MCP-01.
