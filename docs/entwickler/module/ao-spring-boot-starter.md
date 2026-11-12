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

## Code-Beispiele

### Ein minimaler Host

```java
@SpringBootApplication
public class MyHostApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyHostApplication.class, args);
    }

    /** Ersetzt den festen Benutzer: jede Stelle, die eine Benutzer-ID schreibt, nimmt diese Bean. */
    @Bean
    CurrentUser currentUser() {
        return CurrentUser.fixed("data-team", "Data Team");
    }

    /** Ein zusätzlicher Schritt nach jedem Ingest – Auto-Ontology-Beans lassen sich normal injizieren. */
    @Bean
    ApplicationRunner registerStage(IngestService ingest) {
        return args -> ingest.addStage(new IngestService.Stage() {
            @Override
            public void afterIngest(String databaseId, String databaseName) {
                LoggerFactory.getLogger("host").info("Catalog of {} is up to date", databaseName);
            }
        });
    }
}
```

```yaml
# application.yml des Hosts
spring:
  threads.virtual.enabled: true
  mvc.async.request-timeout: 1800s            # Chat-Läufe dauern Minuten
  datasource:
    url: jdbc:postgresql://db:5432/host        # der Store legt seine Tabellen in dieser Datenbank an
    username: host
    password: ${DB_PASSWORD}
ao:
  api-prefix: /ontology                        # alles unter /ontology/…, kollidiert nicht mit Host-Pfaden
  vector: { store: qdrant, dimensions: 1024, qdrant: { host: qdrant, port: 6334 } }
  embedding: { provider: openai }
  models:
    defaults: { endpoint: http://llm:8000/v1, model: qwen3, api-key: "" }
    embed:    { model: bge-m3 }
  ingest:
    connections:
      - name: analytics
        datasource-bean: analyticsDataSource   # eine vorhandene DataSource des Hosts wiederverwenden
        schemas: [public]
```

### Eine Komponente ersetzen

```java
/** Eigenes Gateway, z. B. für einen Anbieter ohne OpenAI-kompatible API. */
@Bean
LlmGateway llmGateway(MyProviderClient client) {
    return new LlmGateway() {
        @Override
        public <T> T structured(ModelRole role, List<LlmMessage> messages, Class<T> type) {
            String schema = Json.write(OutputSchema.jsonSchema(type));      // dasselbe Schema wie im Standard-Gateway
            String raw = client.complete(messages, schema);
            try {
                return StructuredParser.parse(raw, type);                    // strikt, inklusive validate()
            } catch (RuntimeException e) {
                throw new LlmException("Structured call " + type.getSimpleName() + " failed", e);
            }
        }

        @Override
        public String text(ModelRole role, List<LlmMessage> messages) {
            return client.complete(messages, null);
        }
    };
}
```

Weil alle Beans `@ConditionalOnMissingBean` sind, genügt die Definition im Host; in Tests markiert
man sie mit `@Primary`:

```java
@TestConfiguration
static class Stubs {
    @Bean
    @Primary
    LlmGateway scripted() {
        return new ScriptedLlmGateway().on(SQLGenerationModel.class,
                prompt -> new SQLGenerationModel("count rows", "SELECT COUNT(*) FROM SALES.ORDERS", "The number of orders."));
    }
}
```

### Das Schema selbst migrieren

```yaml
ao.store.liquibase.enabled: false
```

```yaml
# db/changelog/host-master.yaml des Hosts
databaseChangeLog:
  - include: { file: db/changelog/host/001-init.sql }
  - include: { file: db/changelog/ao-changelog.yaml }     # liegt im Jar von ao-store
```

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
