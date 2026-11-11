# Entwicklerhandbuch Frontend – Übersicht und Einstieg

Dieses Handbuch beschreibt das Frontend von Auto-Ontology unter `frontend/`. Es ergänzt das
[Backend-Handbuch](../README.md) und die fachliche [Projektdokumentation](../../DOKUMENTATION.md).

| Dokument | Inhalt |
|---|---|
| dieses Dokument | Aufbau, Setup, Architektur, Querschnittsthemen, Tests, Fallstricke |
| [`quasar-ontology.md`](quasar-ontology.md) | Bibliothek `@jentsch/quasar-ontology`: API-Client, Plugin, alle Seiten |
| [`quasar-dashboard-nl.md`](quasar-dashboard-nl.md) | Bibliothek `@jentsch/quasar-dashboard-nl`: NL-Client, `QdNlChartPrompt` |
| [`demo-app.md`](demo-app.md) | Demo-App `ao-demo`: Routen, Boot-Dateien, Integration von quasar-dashboard |

---

## 1. Das Frontend in fünf Sätzen

1. Das Frontend ist **kein** monolithisches SPA, sondern zwei **Vue-Komponentenbibliotheken** und eine
   kleine Demo-App, die sie zusammensetzt.
2. `@jentsch/quasar-ontology` enthält die komplette Auto-Ontology-Oberfläche (Chat, Glossar,
   Katalog, Suche, Analysen, Graph, Analytics, Einstellungen) als einzelne Seitenkomponenten.
3. `@jentsch/quasar-dashboard-nl` enthält die Komponente „Chart aus Frage“ für quasar-dashboard.
4. Beide Bibliotheken kennen **keine Routen und kein Layout**: Ein Host (hier `apps/demo`, später
   quasar-dashboard) bindet die Seiten in seine eigenen Routen ein und installiert die Plugins.
5. Charts werden nie selbst gezeichnet, sondern immer mit `QdChartRenderer` aus
   `@jentsch/quasar-dashboard` – es gibt also nur einen Renderer und einen Viz-Katalog (NFR-QD-08).

---

## 2. Schnellstart

### 2.1 Voraussetzungen

| Werkzeug | Version | Hinweis |
|---|---|---|
| Node.js | 22.12+, 24 oder 26 (`engines` in `frontend/package.json`) | |
| pnpm | ≥ 9 (`packageManager: pnpm@12.16.1`) | `corepack enable` aktiviert die passende Version |
| Nachbarrepository | `C:\Projekte\Text2SQL\quasar-dashboard` | wird als Workspace-Paket und Quellcode eingebunden |
| Backend | läuft auf `http://localhost:8080` | siehe [Backend-Handbuch](../README.md), Abschnitt 2 |

### 2.2 Starten

```bash
cd frontend
pnpm install                 # installiert alle Workspace-Pakete, führt "quasar prepare" aus
pnpm dev                     # Quasar-Dev-Server auf http://localhost:9100
```

Der Dev-Server leitet alles unter `/api` an das Backend weiter (`AO_BACKEND`, Default
`http://localhost:8080`). Das deckt auch `/api/nl-chart` und `/api/v1` (Dashboard-API) ab.

Für „Chart aus Frage“ (`/ask`, `/explore`) muss das Backend `ao-dashboard-demo` laufen, für die
übrigen Seiten reicht `ao-app` mit Profil `demo`.

### 2.3 Prüfen und bauen

```bash
pnpm test                                        # Vitest von @jentsch/quasar-ontology
pnpm --filter @jentsch/quasar-dashboard-nl test  # Vitest von @jentsch/quasar-dashboard-nl
pnpm typecheck                                   # vue-tsc für @jentsch/quasar-ontology
pnpm --filter ao-demo typecheck                  # vue-tsc für die Demo-App
pnpm build                                       # SPA nach apps/demo/dist/spa (Basis des Nginx-Images)
```

---

## 3. Aufbau des Workspaces

```
frontend/
├── package.json             Root-Skripte (dev, build, test, typecheck), Engines, packageManager
├── pnpm-workspace.yaml      Workspace: packages/*, apps/*, ../../quasar-dashboard/packages/{dashboard,mock-backend}
├── tsconfig.base.json       gemeinsame, strenge TypeScript-Optionen
├── packages/
│   ├── ontology/            @jentsch/quasar-ontology
│   │   ├── src/api.ts           API-Client, SSE-Parser, Fehlerklasse
│   │   ├── src/plugin.ts        createQuasarOntology, useAo
│   │   ├── src/results.ts       Zeilen → QueryResult, Chart-Blöcke, CSV, Entitäts-Links
│   │   ├── src/i18n/            en.ts (Basis), de.ts (typgeprüft gegen en)
│   │   ├── src/pages/           8 Seitenkomponenten Ao*Page.vue
│   │   ├── src/components/      AoResultView.vue
│   │   ├── src/index.ts         öffentliche API
│   │   └── test/sse.test.ts
│   └── dashboard-nl/        @jentsch/quasar-dashboard-nl
│       ├── src/plugin.ts        createQuasarDashboardNl, NL-Client, SSE-Parser
│       ├── src/QdNlChartPrompt.vue
│       ├── src/i18n/index.ts    en + de im Namespace qdnl
│       ├── src/index.ts
│       └── test/sse.test.ts
└── apps/
    └── demo/                ao-demo (Quasar-App)
        ├── quasar.config.ts     Aliase auf quasar-dashboard-Quellen, Dev-Proxy, Port 9100
        ├── .env.development     VITE_AO_API_PREFIX=/api
        └── src/                 boot/, layouts/, pages/, router/, open-in-builder.ts
```

### 3.1 Abhängigkeiten zwischen den Paketen

```
                apps/demo (ao-demo)
               ╱        │         ╲
              ▼         ▼          ▼
 @jentsch/quasar-   @jentsch/quasar-   @jentsch/quasar-dashboard
   ontology           dashboard-nl        (../quasar-dashboard/packages/dashboard, aus den Quellen)
        │                  │                  ▲
        └── peerDependency ┴──────────────────┘
```

- Beide Bibliotheken haben `@jentsch/quasar-dashboard`, `vue`, `quasar`, `vue-i18n` (und
  `echarts`/`vue-router` bei ontology) als **peerDependencies**. Der Host liefert genau eine Instanz.
- Die Dashboard-Bibliothek importiert **nie** aus den beiden Paketen (Abhängigkeitsregel von
  quasar-dashboard).
- Die beiden AO-Bibliotheken kennen sich gegenseitig nicht.
- Die Pakete werden als **Quellcode** konsumiert (`"main": "./src/index.ts"`); es gibt keinen
  eigenen Bibliotheks-Build. Vite der Host-App übersetzt sie mit.

### 3.2 quasar-dashboard aus den Quellen

`pnpm-workspace.yaml` nimmt `../../quasar-dashboard/packages/dashboard` und `…/mock-backend` in den
Workspace auf (`workspace:*`). Zusätzlich setzt `apps/demo/quasar.config.ts` Vite-Aliase:

| Import | wird aufgelöst auf |
|---|---|
| `@jentsch/quasar-dashboard` | `quasar-dashboard/packages/dashboard/src/index.ts` |
| `@jentsch/quasar-dashboard/style.css` | `quasar-dashboard/packages/dashboard/src/styles/index.scss` |

und dedupliziert `vue`, `quasar`, `vue-i18n`, `pinia`, `echarts`, `vue-echarts`, `vue-router`, damit
App, Bibliotheken und Dashboard-Quellen dieselben Instanzen nutzen. Änderungen im Nachbarrepository
wirken deshalb sofort im Dev-Server.

---

## 4. Technologie-Stack

| Belang | Technik |
|---|---|
| Framework | Vue 3.5 mit `<script setup lang="ts">` |
| UI-Bibliothek | Quasar 2 (Komponenten `q-*`, Plugins `Notify`, `Dialog`, `Dark`) |
| Build und Dev-Server | `@quasar/app-vite` 3 (Vite 8) |
| Sprache | TypeScript ~5.9, `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `verbatimModuleSyntax` |
| Zustand | lokale `ref`s in den Seiten; **Pinia** nur für die Stores von quasar-dashboard (Chart-Builder, Dashboards) |
| Routing | Vue Router 5 – nur in der App |
| i18n | vue-i18n 11 (Composition API, `legacy: false`); Namespaces `ao`, `qdnl`, `app` |
| Charts | ECharts 6 über `vue-echarts` (Exploration-Graph) und `QdChartRenderer` (alle Ergebnis-Charts) |
| HTTP | `fetch`; SSE über `fetch` + `ReadableStream` |
| Tests | Vitest 5 (Node-Umgebung) |
| Paketmanager | pnpm-Workspace |

---

## 5. Architektur

### 5.1 Schichten einer Bibliothek

```
Host-App ── app.use(createQuasarOntology(options)) ──▶ Plugin
                                                         ├─ provide(AO_CLIENT, createAoClient(options))
                                                         └─ i18n.global.mergeLocaleMessage('en'|'de', …)
Seite (AoChatPage …) ── useAo() ──▶ AoClient ── fetch ──▶ baseUrl + apiPrefix + Pfad ──▶ Backend
            │
            └── AoResultView ── QdChartRenderer (@jentsch/quasar-dashboard)
```

- **Plugin:** stellt per `provide`/`inject` einen API-Client bereit und registriert die Texte in der
  vorhandenen vue-i18n-Instanz des Hosts. Ohne vue-i18n (`legacy: false`) bricht die Installation
  mit einer klaren Fehlermeldung ab.
- **Client:** kennt nur Restpfade (`/terms`, `/chat/completions`). Basis-URL, Präfix, `fetch` und
  Header kommen aus den Plugin-Optionen.
- **Seiten:** halten ihren Zustand lokal (keine Stores), laden in `onMounted` und kommunizieren mit
  dem Host nur über **Props und Events** (z. B. `focus`, `entity`, `section`, `saved`,
  `open-builder`). Navigation ist Sache des Hosts.

### 5.2 Gemeinsame Plugin-Optionen (NFR-QD-06a)

Alle drei Plugins (Dashboard, Ontology, NL) nehmen dieselben Request-Optionen, damit der Host sie
einheitlich konfiguriert:

```ts
const baseUrl = import.meta.env.VITE_API_BASE_URL ?? ''
const aoPrefix = import.meta.env.VITE_AO_API_PREFIX ?? '/api'

app.use(createPinia())
app.use(createQuasarDashboard({ baseUrl }))
app.use(createQuasarOntology({ baseUrl, apiPrefix: aoPrefix }))
app.use(createQuasarDashboardNl({ baseUrl, apiPrefix: `${aoPrefix}/nl-chart` }))
```

| Option | Bedeutung |
|---|---|
| `baseUrl` | Origin des Backends; leer = gleiche Origin (Dev-Proxy bzw. Nginx) |
| `apiPrefix` | muss zu `ao.api-prefix` bzw. `ao.nl-chart.api-prefix` des Backends passen |
| `fetch` | eigenes `fetch`, z. B. für Correlation-IDs, Retries oder Tests |
| `headers` | Objekt oder Funktion (wird bei jedem Request neu ausgewertet) |

Anmeldedaten gibt es nicht; das Backend kennt keine Benutzer.

### 5.3 Streaming (SSE über `fetch`)

Chat-Antworten und NL-Vorschläge kommen als Server-Sent Events. `EventSource` scheidet aus, weil der
Request ein **POST mit JSON-Body** ist. Stattdessen:

```
fetch(POST, Accept: text/event-stream) → response.body.getReader()
   → TextDecoder (stream: true) → Zeilenparser → onEvent(JSON)
   „data: [DONE]“ → Stream beenden
```

Der Parser (`createSseParser` bzw. `parseSse`) puffert unvollständige Zeilen über Chunk-Grenzen,
ignoriert Kommentare (`: ping`) und kaputte Zeilen und stoppt bei `[DONE]`. Abbrechen erfolgt über
ein `AbortSignal`.

Event-Typen und ihre Wirkung in der UI:

| Event | Chat (`AoChatPage`) | NL (`QdNlChartPrompt`) |
|---|---|---|
| `step` (`phase: start`) | neuer Schritt mit Spinner | neuer Schritt |
| `step` (`phase: end`) | Haken, ggf. `thought` darunter | Haken, ggf. `thought` |
| `sql` | SQL des laufenden Turns aktualisieren | – |
| `result` | Antworttext, SQL, Zeilen | – |
| `charts` | Chart-Blöcke (Karussell) | – |
| `chart_suggestion` | – | Vorschlag mit Vorschau |
| `error` | Fehlermeldung im Turn | Fehlerbanner |

### 5.4 Charts

- Das Backend liefert Chart-Blöcke `{viz_type, params, mode, dataset_id?}` (Chat) bzw. einen
  `chart_suggestion` mit `params` und – in Modus B – `results`.
- `rowsToQueryResult` wandelt Ergebniszeilen in das `QueryResult` des Dashboards (`coltypes`
  0 numerisch, 1 Text, 2 zeitlich als Epoch-ms UTC, 3 boolesch; Zahlen auf 15 signifikante Stellen).
- `QdChartRenderer` läuft im **gesteuerten Modus** (`:params` + `:results`) für SQL-Ergebnisse und im
  **params-Modus** (nur `:params`, die Bibliothek lädt die Daten selbst über die Dashboard-API) für
  semantische Vorschläge.

### 5.5 i18n

| Namespace | Paket | Dateien |
|---|---|---|
| `ao.*` | `@jentsch/quasar-ontology` | `src/i18n/en.ts` (Basis), `de.ts` mit Typ `typeof en` |
| `qdnl.*` | `@jentsch/quasar-dashboard-nl` | `src/i18n/index.ts` (`de: typeof en`) |
| `app.*` | Demo-App | `apps/demo/src/boot/i18n.ts` |
| `qd.*` | quasar-dashboard | dort |

Fehlt in `de` ein Schlüssel aus `en`, meldet TypeScript einen Fehler. Die Sprache wählt der Host
(`locale`); die Demo-App nimmt die Browsersprache und bietet einen DE/EN-Schalter.

### 5.6 Theming und Dark Mode

Quasar ist mit `dark: 'auto'` konfiguriert (folgt dem Betriebssystem); die Demo-App hat einen
Umschalter (`Dark.toggle()`). Die Seiten verwenden Quasar-Farben und neutrale Rahmen
(`rgba(128,128,128,0.3)`), damit sie in beiden Modi lesbar sind. Ausnahme: die SQL-Anzeige im Chat
nutzt `bg-grey-2 text-black` und bleibt auch im Dark Mode hell.

---

## 6. Konventionen

- **SPDX-Header** in jeder Datei (`// SPDX-License-Identifier: Apache-2.0` bzw. als HTML-Kommentar in
  `.vue`).
- **Kommentar mit Anforderungs-ID** an jeder Seite/Funktion (z. B. `FR-FE-02`, `FR-NLC-16`).
- **Keine Rohtexte** in Templates: alle Texte über `t('ao.…')` bzw. `t('qdnl.…')`, neue Schlüssel in
  `en` **und** `de`.
- **`data-testid`** mit Präfix `ao-…` bzw. `qdnl-…` an interaktiven und prüfbaren Elementen.
- **Keine Routen in Bibliotheken.** Navigation über Events an den Host.
- **API-Pfade nur relativ** zum Präfix (`ao.get('/terms')`), nie `/api/...` hart kodieren.
- **Typisierung pragmatisch:** API-Antworten sind meist `Record<string, unknown>` (`Row`), weil das
  Backend Maps mit snake_case-Feldern liefert. Feldnamen exakt wie in der API.
- **Destruktive Aktionen** (Reset, Import, Verbindung/Objekt löschen) immer mit Bestätigung.

---

## 7. Typische Aufgaben

| Ich will … | Wo |
|---|---|
| eine neue Seite in die Bibliothek aufnehmen | `packages/ontology/src/pages/AoXyzPage.vue`, Export in `src/index.ts`, Route in `apps/demo/src/router/routes.ts`, Menüeintrag in `MainLayout.vue`, Texte unter `ao.nav` |
| einen neuen API-Aufruf | direkt in der Seite über `useAo()` (`get/post/put/patch/del/upload/download/stream/watch`) |
| ein neues Chat-Event darstellen | `handle()` in `AoChatPage.vue`, Typ `Turn` erweitern |
| die Ergebnisanzeige ändern | `components/AoResultView.vue`, `results.ts` |
| Texte ändern oder übersetzen | `packages/ontology/src/i18n/{en,de}.ts`, `packages/dashboard-nl/src/i18n/index.ts` |
| einen neuen Einstellungsbereich | `AoSettingsPage.vue`: `SECTIONS`, `load()`, Template-Zweig, Texte unter `ao.settings` |
| das API-Präfix ändern | `VITE_AO_API_PREFIX` (Proxy und Plugins) und `AO_API_PREFIX` im Backend |
| gegen ein anderes Backend entwickeln | `AO_BACKEND=http://host:port pnpm dev` |

---

## 8. Tests

| Datei | Prüft |
|---|---|
| `packages/ontology/test/sse.test.ts` | SSE-Parser über Chunk-Grenzen und `[DONE]`; `rowsToQueryResult` (coltypes, Datum, Zeilenzahl); `parseChartBlocks`, `toCsv`, `entityLinks` |
| `packages/dashboard-nl/test/sse.test.ts` | `parseSse` über Chunk-Grenzen; gleiche i18n-Schlüssel in DE und EN |

Vitest läuft in der **Node-Umgebung** (kein DOM). Komponententests, Browser-Tests, Screenshot-
Baselines und Playwright-E2E (NFR-TST-06, NFR-NLC-01) gibt es noch nicht. Für Komponententests
müsste `environment: 'jsdom'` (oder der Browser-Modus von Vitest) plus `@vue/test-utils` ergänzt
werden; Plugins werden dann mit einem gemockten `fetch` installiert:

```ts
const fetch = vi.fn(async () => new Response(JSON.stringify([]), { status: 200 }))
mount(AoChatPage, { global: { plugins: [i18n, createQuasarOntology({ fetch })] } })
```

---

## 9. Fallstricke und bekannte Lücken

| # | Thema | Beschreibung | Wo |
|---|---|---|---|
| 1 | Root-Skripte decken nicht alles ab | `pnpm test` und `pnpm typecheck` laufen nur für `@jentsch/quasar-ontology`; das NL-Paket und die App müssen extra geprüft werden (Abschnitt 2.3) | `frontend/package.json` |
| 2 | Native Browser-Dialoge | Umbenennen und Bestätigungen nutzen `window.prompt`/`window.confirm` (9 Stellen) statt Quasar-Dialogen; nicht übersetzbar gestylt, schwer zu testen | ontology-Seiten |
| 3 | Kein ESLint | Die Regel „keine Rohtexte“ wird nicht automatisch geprüft; einzelne Rohtexte existieren (z. B. Label „Chart“ in `AoResultView`, „SQL“/„PQL“) | ontology |
| 4 | Kein Mock-Modus | `@jentsch/mock-backend` ist Dev-Abhängigkeit der App, wird aber nicht genutzt; MSW-Handler für `/api/nl-chart/**` (FR-NLC-20) fehlen. Das Frontend braucht immer ein laufendes Backend | `apps/demo` |
| 5 | Chat-Charts immer „nur Ansicht“ | `AoResultView` rendert jeden Chart-Block mit den SQL-Zeilen und zeigt den Hinweis „nur Ansicht“; die Aktion „Als Dashboard-Chart speichern“ für semantische Ergebnisse (FR-NLC-22) fehlt | `AoResultView.vue` |
| 6 | Client-Prüfung unvollständig | `QdNlChartPrompt` ergänzt Plugin-Defaults, ruft aber `plugin.validate()` nicht auf (FR-NLC-13) | `QdNlChartPrompt.vue` |
| 7 | Fehler beim Speichern | `save()` im NL-Prompt fängt Fehler der Dashboard-API nicht ab; es erscheint keine Meldung | `QdNlChartPrompt.vue` |
| 8 | Varianten-Text | Varianten-Chips hängen „— als <viz_type>“ (deutsch) an die Frage, auch bei englischer UI | `QdNlChartPrompt.vue` |
| 9 | Slot-Integrationen teilweise | „Mit Frage anpassen“ im Chart-Builder ist umgesetzt; „Chart per Frage hinzufügen“ im Dashboard und „Per Frage abwandeln“ in der Chart-Liste (FR-NLC-18) fehlen | `apps/demo` |
| 10 | Layout in der App | Navigation und Header (`header-actions`-Slot) liegen in `MainLayout.vue` der Demo-App, nicht in der Bibliothek; ein Host baut sie selbst | `apps/demo` |
| 11 | Exploration | Graph mit ECharts-Force-Layout statt sigma.js/graphology; für große Graphen langsamer | `AoExplorationPage.vue` |

---

## 10. Weiter lesen

- [`quasar-ontology.md`](quasar-ontology.md) – Seiten im Detail
- [`quasar-dashboard-nl.md`](quasar-dashboard-nl.md) – „Chart aus Frage“
- [`demo-app.md`](demo-app.md) – wie ein Host alles zusammensetzt
- quasar-dashboard: `quasar-dashboard/docs/developer-guide/` (Plugin-Optionen, `QdChartRenderer`,
  Chart-Builder-Store, Slots)
