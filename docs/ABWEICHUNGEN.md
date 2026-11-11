# Umsetzungsstand und Abweichungen vom Anforderungsdokument

Ergänzt Abschnitt 22 des Anforderungsdokuments um die Abweichungen dieser Umsetzung.

## Abweichungen in der Umsetzung

| Thema | Anforderung | Umsetzung | Grund |
|---|---|---|---|
| Katalog-Metadaten | `information_schema`, `ALL_*`, `sys.*` je Engine | `java.sql.DatabaseMetaData` je Konnektor (liest intern dieselben Views); `MS_Description` bei SQL Server direkt aus `sys.extended_properties` | ein Codepfad für alle Engines, gleiche Spalten wie `base.py` |
| Chart-Empfehlung im Chat | Prompt aus `visualization/prompts.py` | eigener, kurzer Prompt für dashboard-kompatible `ChartParams` | NFR-QD-08 verlangt ein anderes Zielformat als `ChartSpec` |
| Information-Agent | Tool-Schleife mit 13 Tools | Tool-Schleife mit 6 Tools (`list_databases`, `list_tables`, `list_columns`, `list_terms`, `get_term`, `search_semantic_layer`) | reduziert |
| `refine_evidence`, `validate_sql_values`, `precheck_combined` | Knoten nach 9.2 | nicht umgesetzt; der Graph routet wie bei abgeschalteten Proben bzw. ohne Evidenz | im Standard ohnehin inaktiv (FR-AGT-01) |
| Value Anchors | Abschnitt in `prepare_candidates` | nicht umgesetzt | – |
| PII-Erkennung | Regeln, dann LLM-Fallback | nur Regeln (portiert aus `rules.py`); unentschiedene Spalten bleiben ungetaggt | – |
| Import/Export | natives YAML und Apache Ossie | natives YAML (Terms, Attribute, SqlAttributes, Analysen); Ossie antwortet mit 422 | – |
| Viz-Katalog | aus der Bibliothek generiert (`listVizPlugins()`) | handgeschriebene Datei `ao-nl-chart/src/main/resources/nl-chart/viz-catalog.json` für die 8 eingebauten Typen | kein Export-Skript in quasar-dashboard |
| `buildQuery`-Port | gegen exportierte Fixtures getestet | Unit-Tests je Typ, Validierung zusätzlich über die echte Dashboard-Engine (`ChartDataService`, `result_type: query`) | keine Fixture-Datei vorhanden |
| NL-Paket | `quasar-dashboard/packages/dashboard-nl` | `auto-ontology-java/frontend/packages/dashboard-nl` (die Anforderung erlaubt ein eigenes Repository) | quasar-dashboard bleibt unverändert |
| Wertreparatur bei Synonymen (FR-NLC-10) | z. B. „Deutschland“ → `Germany` | Groß/Klein und Tippfehler serverseitig; Übersetzungen übernimmt das LLM per Prompt | – |
| Store-Liquibase | Hosts mit eigenem Liquibase | eigener Bean `aoLiquibase` mit eigenen Changelog-Tabellen; Boots `LiquibaseAutoConfiguration` tritt dadurch zurück | Koexistenz mit Host-Migrationen |
| Offline-Demo (FR-DEMO-06) | `RecordedLlmGateway` mit Aufzeichnungen | `ScriptedLlmGateway` für Tests; Hash-Embedding ohne Netz; keine Aufzeichnung | – |

## Offen

- **FR-NLC-24:** Oracle- und SQL-Server-Dialekte in der Query-Engine von quasar-dashboard. Das gehört in das Repository quasar-dashboard und wurde dort nicht geändert. Modus A läuft deshalb nur auf Dashboard-Datasets in PostgreSQL und H2.
- **Kumo und interaktiver Klärungsmodus:** P3, nicht umgesetzt.
- **Vault:** nicht umgesetzt.
- **Weitere Konnektoren:** P3, nicht umgesetzt.
- **Benchmark-Modus:** P3, nicht umgesetzt.
- **Helm-Chart:** nicht vorhanden.
- **JaCoCo-Schwellen 90/80:** konfiguriert, aber nicht an `check` gebunden. Die Abdeckung erreicht sie noch nicht.
- **Integrationstests:** Qdrant, Oracle Free und SQL Server laufen im Task `integrationTest`. Sie sind geschrieben, wurden aber ohne Docker nicht ausgeführt.
- **Frontend-Tests:** keine Playwright-Tests und keine Screenshot-Baselines; vorhanden sind Vitest-Unit-Tests.
- **Demo-Reset:** `POST /api/demo/reset` fehlt, es gibt nur `POST /api/demo/reingest`.
