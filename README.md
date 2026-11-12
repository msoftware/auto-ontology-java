# Auto-Ontology (Java/Quasar rebuild)

Rebuild of NVIDIA Auto-Ontology (Apache 2.0, see `LICENSE`, `NOTICE`, `OPEN_SOURCE_NOTICE`) with Spring Boot 4.1 /
Java 21 / Jackson 3 / LangChain4j and Vue 3 / Quasar 2, embeddable into `../quasar-dashboard`.
Requirements: `../ANFORDERUNGEN_auto-ontology-java.md`; implementation status and deviations: `docs/ABWEICHUNGEN.md`.
Full project documentation (German): `docs/DOKUMENTATION.md`; backend developer handbook per module: `docs/entwickler/README.md`; frontend developer handbook: `docs/entwickler/frontend/README.md`.

> **Internal tool without authentication.** Everybody who reaches the app has full access (connections, reset,
> import). Never expose it to the internet. Without a container it binds to `127.0.0.1`; the compose files publish
> ports on `127.0.0.1` only.

## Layout

```
backend/   Gradle (Kotlin DSL, version catalog), includes ../quasar-dashboard/backend as composite build
  ao-common ao-store ao-vector ao-llm ao-connectors ao-ingestion ao-semantic ao-agent ao-api ao-mcp
  ao-nl-chart              NL charts for quasar-dashboard (section 23)
  ao-spring-boot-starter   all modules as auto-configuration (the NL module is added separately)
  ao-app                   standalone host (profiles: default, demo, qdrant, worker)
  ao-dashboard-demo        combined host: quasar-dashboard backend + sample data + Auto-Ontology + NL charts
frontend/  pnpm workspace
  packages/ontology        @jentsch/quasar-ontology  (chat, terms, data, discovery, analysis, exploration, …)
  packages/dashboard-nl    @jentsch/quasar-dashboard-nl  (QdNlChartPrompt)
  apps/demo                Quasar app (routes /chat … /settings, /ask, /explore)
demo/      demo questions with expected values, NL-chart golden set, compose file with Qdrant
docker/    Dockerfiles and nginx config
```

## Model endpoints

All OpenAI-compatible (NIM, vLLM, Ollama, LM Studio, LiteLLM, Azure OpenAI): `DEFAULT_MODELS_ENDPOINT`,
`DEFAULT_MODELS_MODEL`, `DEFAULT_MODELS_API_KEY` (empty allowed), per role `REASONING_*`, `NON_REASONING_*`,
`EMBED_*`, `RERANK_*`. Structured output per role: `ao.models.<role>.structured-output` =
`json_schema` | `json_object` | `tool_calling` | `tool_calling_no_choice`.

At startup the backend logs the effective configuration (`d.j.a.llm.ConfigurationReport`): profiles, store, the
resolved endpoint/model/structured output per role, the first 10 characters of each API key (at most half of a
short key) with its length, vector store, connections (passwords masked), agent and NL-chart settings. Switch it off
with `ao.log-configuration=false`.

A persistent H2 store must not use `DATABASE_TO_LOWER=TRUE`: Liquibase cannot find its changelog table again after a
restart, so the store refuses such a URL with a clear message (in-memory H2 is fine).

## Demo (H2, no external services)

```bash
cd backend
DEFAULT_MODELS_ENDPOINT=http://localhost:11434/v1 DEFAULT_MODELS_MODEL=qwen3 \
  ./gradlew :ao-app:bootRun --args='--spring.profiles.active=demo'
cd ../frontend && pnpm install && pnpm dev          # http://localhost:9100 (proxies /api to :8080)

curl -N -X POST localhost:8080/api/chat/completions -H 'Content-Type: application/json' \
     -d '{"question":"How many wholesale customers are there?"}'
```

The demo ingests the H2 "Sales" database, detects PII and compiles the semantic layer at start. Without an LLM the
ingest and the compilation still run (fallback terms); chat answers need an LLM. Embeddings default to the offline hash
embedder (`AO_EMBEDDING_PROVIDER=openai` plus `EMBED_*` and `AO_VECTOR_DIMENSIONS` for a real model).
Demo questions with expected values: `demo/questions.yaml`.

## Charts from questions (quasar-dashboard)

```bash
cd backend && ./gradlew :ao-dashboard-demo:bootRun    # dashboard API /api/v1, Auto-Ontology /api, NL /api/nl-chart
cd ../frontend && pnpm dev                            # /ask and /explore
```

## MCP

Read-only MCP server (Streamable HTTP) at `http://localhost:8080/mcp` with the tools `check_readiness`,
`search_terms`, `get_term`, `get_term_columns`, `get_term_sql_attributes`, `get_sql_attribute`, `describe_table`,
`check_answerable`, `ask_question`.

## Docker

```bash
(cd backend && ./gradlew :ao-app:bootJar) && (cd frontend && pnpm install && pnpm build)
docker compose up --build                             # PostgreSQL + Qdrant + backend + frontend (127.0.0.1:9000)
```

## Build and tests

```bash
cd backend && ./gradlew test             # H2 + embedded PostgreSQL, no Docker (incl. E2E, OpenAPI contract, MCP, NL charts)
cd backend && ./gradlew integrationTest  # Testcontainers: Qdrant, Oracle Free, SQL Server
cd frontend && pnpm test && pnpm typecheck && pnpm --filter ao-demo typecheck && pnpm build
```
