# Modul `ao-dashboard-demo`

Paket `de.jentsch.autoontology.dashboarddemo` · [Zurück zur Übersicht](../README.md)

## Zweck

Ein **kombinierter Host**, der das quasar-dashboard-Backend mit seinen Beispieldaten,
Auto-Ontology und das NL-Chart-Modul in einem Prozess auf H2 startet (FR-NLC-23). Er dient als Demo
für „Chart aus Frage“ und als Integrationstest beider Systeme. Gleichzeitig ist er das Muster, wie
ein echter Dashboard-Host Auto-Ontology einbindet.

## Abhängigkeiten

`implementation`: `ao-spring-boot-starter`, `ao-nl-chart`,
`de.jentsch.quasardashboard:quasar-dashboard-backend-sample-data` (Composite Build).
`runtimeOnly`: H2. Plugin `org.springframework.boot`; `bootRun` mit Arbeitsverzeichnis
`auto-ontology-java`.

## Klassen

| Klasse | Aufgabe |
|---|---|
| `DashboardDemoApplication` | Main-Klasse; Beans `sampleData` und `sampleDataInstalled` |
| `DashboardDemoApplication.SampleDataInstalled` | Marker-Record: Beispieldaten sind installiert |

`sampleDataInstalled` hängt vom `MetadataSchemaInitializer` des Dashboards ab (die `qd_*`-Tabellen
existieren dann), installiert die Beispieldaten (`SampleData.install()`) und seedet
`semantic_compilation_enabled` und `visualization_enabled`. Das passiert während der
Kontexterzeugung und damit **vor** dem ersten Ingest.

## Konfiguration (`application.yml`)

```yaml
spring.datasource.url: jdbc:h2:mem:quasar-dashboard-demo;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
quasar-dashboard:
  api-prefix: /api/v1
  fixed-now: 2026-06-30T00:00:00Z
ao:
  api-prefix: /api
  user: { id: demo }
  vector: { store: memory, dimensions: 768 }      # Profil "qdrant" schaltet auf Qdrant
  embedding: { provider: hash }
  ingest:
    connections:
      - name: analytics
        datasource-bean: dataSource               # dieselbe In-Memory-H2 wie das Dashboard
        schemas: [public, hr, main]
        table-allow-regex: ^(sales|web_traffic|employees|inventory)$
  dashboard:
    database-mapping: { analytics: analytics, warehouse: analytics }   # n:1
```

Bemerkenswert:

- **Eine** In-Memory-H2 für alles: Dashboard-Metadaten (`qd_*`), Faktentabellen, AO-Store. Der
  AO-Ingest wird per Allow-Regex auf die vier Faktentabellen beschränkt.
- Die Quelle `analytics` nutzt die vorhandene `DataSource`-Bean (`Connectors.wrap`), weil eine
  In-Memory-Datenbank nur im selben Prozess erreichbar ist.
- `DATABASE_TO_LOWER=TRUE` ist erlaubt, weil der Store nicht persistent ist.
- Die Uhr kommt aus `quasar-dashboard.fixed-now` (Bean `quasarDashboardClock`), Auto-Ontology
  übernimmt sie über `AoTime`.
- Die Präfixe `/api` (AO), `/api/nl-chart` (NL) und `/api/v1` (Dashboard) kollidieren nicht; die
  Startprüfung in `ao-common` stellt das sicher.

## Starten

```bash
cd backend && DEFAULT_MODELS_ENDPOINT=… DEFAULT_MODELS_MODEL=… ./gradlew :ao-dashboard-demo:bootRun
cd ../frontend && pnpm dev            # Routen /ask (QdNlChartPrompt) und /explore (Chart-Builder)
```

Erwartbar: Die Faktentabellen sind denormalisiert und haben keine FKs; Semantic-FK- und
Bridge-Stufe liefern deshalb nichts. Der Dataset-Sync übernimmt die Metriken (`count`,
`total_revenue`, `avg_order_value`, `total_visits`, `avg_bounce_rate`, `headcount`, `avg_salary`,
`total_stock`) als SqlAttributes.

## Tests

`NlChartIntegrationTest` – startet den ganzen Host mit gestubbtem LLM: Dataset-Sync (Links,
Metriken), ein Vorschlag über `/api/nl-chart/suggest` und die Validierung gegen die echte
Dashboard-Engine.

## Als Vorlage für einen echten Host

1. `ao-spring-boot-starter` und `ao-nl-chart` neben `quasar-dashboard-backend` einbinden.
2. Analyse-Datenbank per `datasource-bean` (gleicher Pool, gleicher Lesebenutzer) oder per URL als
   AO-Verbindung konfigurieren; Dashboard-Metadaten per Regex oder Schema-Allowlist ausschließen.
3. `ao.dashboard.database-mapping` für alle Dashboard-Datenbanken setzen.
4. Keine Security für `/api/**` und `/api/nl-chart/**` (bzw. `permitAll`).
5. Im Frontend `createQuasarDashboard`, `createQuasarOntology` und `createQuasarDashboardNl` mit
   denselben Request-Optionen installieren.

## Fallstricke

- Das Golden Set (`demo/nl-chart-questions.yaml`) ist auf diese Beispieldaten und `fixed-now`
  abgestimmt.
- Modus A läuft nur, solange die Datasets in H2 oder PostgreSQL liegen.
- Der Composite Build braucht das Nachbarrepository `quasar-dashboard` in einem kompatiblen Stand.

## Anforderungsbezug

FR-NLC-01, FR-NLC-23, NFR-QD-04 bis NFR-QD-06.
