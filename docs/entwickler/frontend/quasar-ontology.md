# Paket `@jentsch/quasar-ontology`

Verzeichnis `frontend/packages/ontology` · [Zurück zur Frontend-Übersicht](README.md)

## Zweck

Die komplette Oberfläche von Auto-Ontology als **Quasar-Komponentenbibliothek** (NFR-QD-07):
Chat, Glossar, Datenkatalog, globale Suche, Analysen, Graph-Exploration, Nutzungsbericht und
Einstellungen. Ein Host bindet die Seitenkomponenten in eigene Routen ein. Vorbild sind die Seiten
des Next.js-Originals (`auto-ontology/frontend/app/**`, `components/*Page/`) und der Aufbau von
`quasar-dashboard/packages/dashboard`.

## Abhängigkeiten

| Art | Pakete |
|---|---|
| peer | `@jentsch/quasar-dashboard` (workspace), `echarts` ^6, `quasar` ^2.17, `vue` ^3.5, `vue-i18n` ≥ 10 < 12, `vue-router` ≥ 4 < 6 |
| dev | dieselben plus `vue-echarts`, `vite`, `vitest`, `vue-tsc`, `@vitejs/plugin-vue` |

`vue-router` ist peer, wird von den Seiten aber nicht importiert; die Seiten navigieren über Events.

## Öffentliche API (`src/index.ts`)

```ts
// Plugin und Client
createQuasarOntology, useAo, AO_CLIENT
createAoClient, createSseParser, AoApiError
type QuasarOntologyOptions, AoClient, AoEvent
// Hilfsfunktionen
rowsToQueryResult, parseChartBlocks, toCsv, entityLinks, type ChartBlock
// Texte
aoMessages
// Seiten und Komponenten
AoChatPage, AoTermsPage, AoDataPage, AoDiscoveryPage, AoAnalysisPage,
AoExplorationPage, AoAnalyticsPage, AoSettingsPage, AoResultView
```

Alles, was nicht hier exportiert ist, ist intern.

## Plugin (`src/plugin.ts`)

```ts
app.use(i18n)                                    // vue-i18n mit legacy: false – muss vorher installiert sein
app.use(createQuasarOntology({
  baseUrl: '',                                   // Origin des Backends
  apiPrefix: '/api',                             // = ao.api-prefix
  fetch: myFetch,                                // optional
  headers: () => ({ 'x-correlation-id': id() }), // optional
  source: 'app',                                 // Analytics-Quelle, Default 'app'
}))
```

`install` stellt den Client unter `AO_CLIENT` bereit und mischt `ao.*`-Texte für `en` und `de` in die
globale i18n-Instanz. Die Instanz wird über das interne Symbol von vue-i18n bzw.
`I18nInjectionKey` gesucht; fehlt sie, wirft das Plugin
`[@jentsch/quasar-ontology] vue-i18n (legacy: false) must be installed first`.

In Komponenten: `const ao = useAo()` (wirft, wenn das Plugin fehlt).

## API-Client (`src/api.ts`)

| Methode | Verhalten |
|---|---|
| `url(path)` | `baseUrl + apiPrefix + path` |
| `get/post/put/patch/del<T>(path, body?)` | JSON-Request; `post`/`put`/`patch` senden ohne Body ein `{}`. Antwort wird als JSON geparst, sonst als Text zurückgegeben |
| `upload<T>(path, formData)` | Multipart-POST (Modell-Import) |
| `download(path, body)` | POST, Antwort als `Blob` (Modell-Export) |
| `stream(path, body, onEvent, signal?)` | POST mit `Accept: text/event-stream`, SSE-Events an `onEvent` |
| `watch(path, onEvent, signal?)` | GET-SSE (Wiederanhängen an einen laufenden Chat) |

Jeder Request sendet die Host-Header plus `x-auto-ontology-source` (Default `app`).

**Fehler:** Antworten mit Status ≥ 400 werden zu `AoApiError(message, status, body)`. Die Meldung
kommt aus `detail` oder `error` des Bodys (bei Validierungsfehlern die `msg`-Felder verbunden), sonst
aus dem Klartext oder `"<status> <statusText>"`. So funktionieren beide Fehlerformate des Backends
(FastAPI- und Next.js-Stil).

**SSE-Parser:** `createSseParser(onEvent)` liefert `push(chunk): boolean` (true nach `[DONE]`) und
`flush()`. Nur `data:`-Zeilen werden ausgewertet; Kommentare (`: ping`) und unparsbares JSON werden
ignoriert. Nach `[DONE]` bricht `readStream` den Reader ab.

## Hilfsfunktionen (`src/results.ts`)

| Funktion | Zweck |
|---|---|
| `rowsToQueryResult(rows, sql?)` | Ergebniszeilen → `QueryResult` von quasar-dashboard. `coltypes` werden aus dem ersten Nicht-null-Wert je Spalte abgeleitet: Zahl → 0, Boolean → 3, ISO-Datum/-Zeit → 2 (Epoch-ms UTC; reine Daten als `T00:00:00Z`), numerischer String → 0, sonst 1. Zahlen auf 15 signifikante Stellen |
| `chartParams(block)` | Chart-Block → `ChartParams` (`viz_type` + `params`) |
| `parseChartBlocks(content)` | ` ```chart\n{…}\n``` `-Blöcke einer gespeicherten Nachricht → `ChartBlock[]` |
| `toCsv(rows)` | CSV mit Quoting von `"`, `,` und Zeilenumbrüchen |
| `entityLinks(text)` | `[[[Name/id]]]` → Teile `{text, id?}` für Links im Antworttext |

## Seiten

Alle Seiten tragen ein Wurzelelement mit `data-testid="ao-<seite>"` und halten ihren Zustand lokal.

| Komponente | Route im Original | Props | Events | Backend-Aufrufe |
|---|---|---|---|---|
| `AoChatPage` | `/chat` | `focus?: string \| null`, `suggestions?: string[]` | `focus(id)`, `entity(id)` | `/conversations`, `/conversations/{id}`, `/chat/completions` (SSE), `/chat/watch` (SSE), `/chat/cancel`, `/semantic-compilation/status` |
| `AoTermsPage` | `/terms` | `termId?: string \| null` | – | `/terms`, `/terms/{id}`, `…/column-attributes`, `…/sql-attributes`, `/sql-attributes/**` |
| `AoDataPage` | `/data` | – | – | `/datasources/dbs`, `/schemas/{db}`, `/tables/{schema}`, `/columns/{table}`, `/nodes/{id}` |
| `AoDiscoveryPage` | `/discovery` | – | – | `/search/global-search`, `…/count`, `/tags`, `/rules` |
| `AoAnalysisPage` | `/analysis` | – | – | `/custom-analyses/**`, `/pql-analyses/**` |
| `AoExplorationPage` | `/exploration` | – | – | `/exploration/semantic-graph`, `/exploration/graph`, `/exploration/tables/zones`, `/exploration/{kind}/{id}/details` |
| `AoAnalyticsPage` | `/analytics` | – | – | `/analytics?limit=500` |
| `AoSettingsPage` | `/settings/*` | `section?: string` | `section(name)` | je Abschnitt, siehe unten |

### `AoChatPage`

**Datenmodell:** Eine Liste von `Turn`s je Konversation:

```ts
interface Turn {
  question: string
  answer?: string            // undefined = noch keine Antwort (läuft oder abgebrochen)
  sql?: string | null
  rows?: Record<string, unknown>[] | null
  charts?: ChartBlock[]
  steps: { node: string; label: string; thought?: string; done: boolean }[]
  error?: string
  running?: boolean
}
```

**Ablauf beim Senden:**

1. Ohne aktive Konversation erzeugt die Seite eine UUID (`crypto.randomUUID()`) und meldet sie per
   `focus` an den Host (das Backend legt die Konversation beim ersten Request an).
2. Ein neuer `Turn` mit `running: true` wird angehängt.
3. `ao.stream('/chat/completions', {question, conversation_id}, …)`; jedes Event läuft durch
   `handle(turn, event)` (Tabelle in der [Übersicht](README.md), Abschnitt 5.3), danach wird ans Ende
   gescrollt.
4. Am Ende `running = false` und die Konversationsliste neu laden.

**Wiederaufnahme nach Reload:** `openConversation(id)` baut die Turns aus den gespeicherten
Nachrichten auf (erste Assistenten-Nachricht = Antwort + SQL, zweite = Chart-Blöcke bzw.
`sql_response`). Hat die letzte Frage noch keine Antwort, hängt sich die Seite per
`/chat/watch?conversation_id=…` an; das Backend spielt alle Events von Anfang an ab.

**Abbrechen:** `POST /chat/cancel` und danach `AbortController.abort()`. Ein `AbortError` wird nicht
als Fehler angezeigt.

**Weiteres:** Banner „Semantic layer not ready“, wenn `/semantic-compilation/status` noch
`calculated: false` liefert; Vorschlags-Chips aus `suggestions` bei leerer Konversation;
Enter sendet, Shift+Enter fügt eine Zeile ein; Entitäts-Links im Antworttext lösen `entity(id)` aus
(die Demo-App navigiert dann zu `/terms?id=…`); Umbenennen und Löschen in der Seitenleiste.

### `AoResultView`

Props: `rows`, `charts?`, `sql?`. Zeigt die Zeilenzahl, einen CSV-Download und – wenn Chart-Blöcke
vorhanden sind – einen Umschalter Chart/Tabelle. Mehrere Charts erscheinen als Karussell. Kommen die
Charts in einem Live-Lauf erst nach den Zeilen, wechselt die Ansicht automatisch auf den Chart.
Gerendert wird mit `QdChartRenderer` im gesteuerten Modus (`:params="chartParams(c)"`,
`:results="[rowsToQueryResult(rows, sql)]"`). Die Tabelle ist eine `q-table` mit Spalten aus den
Schlüsseln der ersten Zeile.

### `AoTermsPage`

Liste (bis 200 Terms, Suche) mit Zählern je Term (Attribute, SQL Attributes, Tabellen) und
Detailbereich: Name/Beschreibung bearbeiten und zertifizieren, ColumnAttributes bearbeiten und
zertifizieren, SQL Attributes anlegen, bearbeiten (`PUT` ersetzt den Ausdruck), validieren
(`/sql-attributes/validate`; 422 wird als Fehlermeldung angezeigt), Beschreibung vorschlagen lassen
(LLM), zertifizieren und löschen. `termId` öffnet einen Term direkt (Deep-Link).

### `AoDataPage`

Lazy geladener Baum (`q-tree`) Database → Schema → Table. Für den gewählten Knoten: Beschreibung,
Zertifizierung, Owner (über `PATCH /nodes/{id}`); bei Tabellen zusätzlich eine Spaltentabelle mit
Typ, editierbarer Beschreibung, Beispielwerten, Tags und Zertifizierung je Spalte.

### `AoDiscoveryPage`

Suchfeld (Debounce 300 ms), Filter nach Objekttyp und Tags, optional Suche in Beschreibungen,
Trefferzähler je Typ. Die aktuelle Suche lässt sich als **Regel** mit Tags speichern
(`POST /rules`).

### `AoAnalysisPage`

Umschalter Custom Analyses (SQL) / PQL Analyses; Liste, Suche (nur SQL), Dialog zum Anlegen und
Bearbeiten. SQL wird vor dem Speichern mit `/custom-analyses/validate` geprüft.

### `AoExplorationPage`

ECharts-Graph (`GraphChart`, Force-Layout, Zoom und Verschieben) mit Umschalter Semantik/Daten;
in der Datenansicht optional mit Spalten. Tooltips zeigen Typ, Name und Zonen der Tabelle. Ein Klick
lädt die Detailkarte über `/exploration/{tables|columns|terms|…}/{id}/details`.

### `AoAnalyticsPage`

Tabelle der letzten 500 Einträge aus `/analytics` mit Benutzer, Quelle (`app`, `api`, `mcp`,
`nl-chart`), Frage, Lösungsschritten (`reasoning`), SQL und Zeitpunkt (sortierbar).

### `AoSettingsPage`

Linke Liste der Abschnitte, rechts der Inhalt. Der Abschnitt kommt über `section` und wird per Event
`section` an den Host gemeldet (die Demo-App spiegelt ihn in die URL `/settings/<abschnitt>`).

| Abschnitt (`SECTIONS`) | Inhalt | Backend |
|---|---|---|
| `connections` | Liste, Formular (URL, Benutzer, Passwort, Schemas, Allow/Deny-Regex), **Test** (liefert Schemas), Anlegen, Löschen; Hinweis bei Verbindungen aus der Umgebung | `/connections/**` |
| `compilation` | Statuskarten „Is Running“, „Failed“, „Is Ready“ (alle 3 s aktualisiert), Schalter Kompilierung und Distinct-Proben, **Reset** mit Bestätigung | `/semantic-compilation/*`, `/configurations/*` |
| `agent` | „Visualize SQL Results“, SQL-Timeout | `/configurations/visualization`, `/configurations/sql-query-timeout` |
| `semanticInput` | Akronyme und Custom Prompts | `/acronyms`, `/custom-prompts` |
| `pii` | PII-Erkennung an/aus | `/configurations/pii-detection` |
| `tags`, `rules`, `zones`, `groups` | anlegen, umbenennen, löschen; Zonen mit Farbe, Zielen und Aktiv-Schalter | `/tags`, `/rules`, `/zones`, `/groups` |
| `importExport` | Export als YAML-Download, Import per Datei (mit Bestätigung, `replace=true`) | `/model/export`, `/model/import` |

Alle Aktionen laufen über `run(fn, okMessage?)`, das Fehler (`AoApiError.message`) und
Erfolgsmeldungen in der Seite anzeigt.

## Texte (`src/i18n`)

`en.ts` ist die Basis (Namespace `ao` mit Gruppen `nav`, `common`, `chat`, `terms`, `data`,
`discovery`, `analysis`, `exploration`, `analytics`, `settings`). `de.ts` ist als `typeof en`
typisiert – ein fehlender Schlüssel ist ein Compilerfehler. Neue Texte immer in beiden Dateien
ergänzen.

## Eine neue Seite hinzufügen

1. `src/pages/AoXyzPage.vue` mit `<script setup lang="ts">`, `useAo()`, `useI18n()`; Wurzelelement
   mit `data-testid="ao-xyz"`.
2. Navigation nur über Events (`defineEmits`), Deep-Links über Props.
3. Texte unter `ao.xyz` in `en.ts` und `de.ts`, Menüname unter `ao.nav.xyz`.
4. Export in `src/index.ts`.
5. Im Host: Route und Menüeintrag (siehe [`demo-app.md`](demo-app.md)).

## Tests

`test/sse.test.ts` (Vitest, Node): SSE-Parser, `rowsToQueryResult`, `parseChartBlocks`, `toCsv`,
`entityLinks`. Für die Seiten gibt es keine Tests.

```bash
pnpm --filter @jentsch/quasar-ontology test
pnpm --filter @jentsch/quasar-ontology typecheck
```

## Fallstricke

- `window.prompt`/`window.confirm` für Umbenennen und Bestätigungen (Chat, Terms, Analysen,
  Einstellungen) statt Quasar-Dialogen.
- `AoResultView` behandelt jeden Chart-Block als SQL-Chart (gesteuerter Modus mit den Chat-Zeilen)
  und zeigt immer den Hinweis „nur Ansicht“; ein Speichern als Dashboard-Chart fehlt.
- Rohtexte: Label „Chart“ in `AoResultView`, „SQL“/„PQL“ in `AoAnalysisPage`, „SQL“ als Spaltentitel
  in `AoAnalyticsPage`.
- `AoTermsPage` lädt höchstens 200 Terms ohne Paginierung.
- `upload` und `download` werten Fehlerkörper nur einfach aus (`download` meldet nur den Status).
- Die Seiten laden ihre Daten bei jedem Mounten neu; es gibt keinen Cache.

## Anforderungsbezug

NFR-FE-01, NFR-QD-06a, NFR-QD-07, NFR-QD-08, FR-FE-01 bis FR-FE-05, FR-CHAT-03 bis -05,
FR-GOV-01 bis -13, FR-NLC-14 (Umwandlung in `QueryResult`).
