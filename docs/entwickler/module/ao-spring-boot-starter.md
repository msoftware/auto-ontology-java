# Modul `ao-spring-boot-starter`

Artefakt `de.jentsch.autoontology:ao-spring-boot-starter` · [Zurück zur Übersicht](../README.md)

## Zweck

Eine einzige Abhängigkeit, die Auto-Ontology vollständig in einen Spring-Boot-Host bringt
(NFR-QD-03). Das Modul enthält **keinen Code**; es bündelt nur Abhängigkeiten.

```kotlin
// build.gradle.kts
dependencies {
    api(project(":ao-api"))   // und damit transitiv: agent, semantic, ingestion, store, vector, llm, connectors, common
    api(project(":ao-mcp"))
}
```

Nicht enthalten ist `ao-nl-chart`, weil es `quasar-dashboard-backend` voraussetzt. Hosts mit
Dashboard binden es zusätzlich ein.

## Einbinden in einen Host

```kotlin
dependencies {
    implementation("de.jentsch.autoontology:ao-spring-boot-starter:0.1.0-SNAPSHOT")
    implementation("de.jentsch.autoontology:ao-nl-chart:0.1.0-SNAPSHOT")   // optional
    runtimeOnly("org.postgresql:postgresql")                               // Treiber für den Store
}
```

Alle Module werden mit `maven-publish` veröffentlicht (`./gradlew publishToMavenLocal`).

Der Host muss liefern:

| Was | Wie |
|---|---|
| eine primäre `DataSource` für den Store | `spring.datasource.*` (PostgreSQL oder H2 mit `MODE=PostgreSQL`) |
| Modell-Endpunkte | `ao.models.*` bzw. `DEFAULT_MODELS_*` |
| Vektorstore | `ao.vector.store` und `ao.vector.qdrant.*` bzw. `memory` |
| Quellverbindungen | `ao.ingest.connections[]`, `CONNECTION_STRINGS` oder UI |

Und beachten:

- **Kein Spring Security** für die AO-Pfade (bzw. `permitAll`); Auto-Ontology erwartet keinen
  angemeldeten Benutzer.
- **Eigene Liquibase-Migrationen:** Der Starter registriert eine eigene `SpringLiquibase`-Bean
  (`aoLiquibase`); Boots `LiquibaseAutoConfiguration` tritt dann zurück. Ein Host mit eigenem
  Liquibase definiert seine `SpringLiquibase`-Bean selbst. Flyway im Host ist unproblematisch.
- **Pfade:** `ao.api-prefix` darf nicht mit Host-Pfaden kollidieren; `/internal/**`, `/health`,
  `/health/live` und `/mcp` sind feste Pfade des Starters.
- **Virtual Threads** (`spring.threads.virtual.enabled=true`) und ein langes MVC-Async-Timeout
  (Chat-Läufe dauern Minuten) wie in `ao-app` setzen.

## Komponenten abschalten oder ersetzen

| Ziel | Mittel |
|---|---|
| MCP-Server aus | `ao.mcp.enabled=false` |
| Schema-Migration aus (Host migriert selbst) | `ao.store.liquibase.enabled=false` und `ao-changelog.yaml` einbinden |
| Scheduler aus | `ao.ingest.scheduler-enabled=false`, `ao.semantic.scheduler-enabled=false` |
| Konfigurationsbericht aus | `ao.log-configuration=false` |
| Komponente ersetzen | eigene Bean desselben Typs (`@ConditionalOnMissingBean` überall) |

## Anforderungsbezug

NFR-QD-03, NFR-ARC-01, NFR-QD-06.
