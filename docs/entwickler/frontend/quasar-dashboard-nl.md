# Paket `@jentsch/quasar-dashboard-nl`

Verzeichnis `frontend/packages/dashboard-nl` · [Zurück zur Frontend-Übersicht](README.md)

## Zweck

„Chart aus Frage“ für quasar-dashboard (Abschnitt 23 der Anforderungen): Eine Komponente
`QdNlChartPrompt` nimmt eine Frage entgegen, zeigt den Fortschritt, rendert den Vorschlag des
Backends mit `QdChartRenderer` und speichert ihn über die **Dashboard-API** als normalen Chart oder
übergibt ihn an den Chart-Builder.

Das Paket liegt in diesem Repository (nicht in quasar-dashboard), damit quasar-dashboard unverändert
bleibt. Backend-Gegenstück: [`ao-nl-chart`](../module/ao-nl-chart.md).

## Abhängigkeiten

| Art | Pakete |
|---|---|
| peer | `@jentsch/quasar-dashboard` (workspace), `quasar` ^2.17, `vue` ^3.5, `vue-i18n` ≥ 10 < 12 |
| dev | dieselben plus `typescript`, `vitest` |

Genutzt aus quasar-dashboard: `QdChartRenderer`, `getVizPlugin` (Plugin-Defaults), Typen
`ChartParams` und `QueryResult`. quasar-dashboard importiert nie aus diesem Paket.

## Öffentliche API (`src/index.ts`)

```ts
createQuasarDashboardNl, createNlClient, useNlClient, parseSse, QDNL_CLIENT
type QuasarDashboardNlOptions, NlClient, NlEvent
qdnlMessages
QdNlChartPrompt
```

## Plugin und Client (`src/plugin.ts`)

```ts
app.use(createQuasarDashboardNl({
  baseUrl: '',
  apiPrefix: '/api/nl-chart',        // = ao.nl-chart.api-prefix
  dashboardApiPrefix: '/api/v1',     // = quasar-dashboard.api-prefix (zum Speichern)
  fetch, headers,                    // optional, wie bei den anderen Plugins
}))
```

| Client-Methode | Request |
|---|---|
| `suggest(body, onEvent, signal?)` | `POST {apiPrefix}/suggest`, SSE über `fetch` + `ReadableStream`, Parser `parseSse` |
| `datasets()` | `GET {apiPrefix}/datasets` → `data` |
| `saveChart(body)` | `POST {dashboardApiPrefix}/chart/` – das NL-Modul hat bewusst **keinen** eigenen Speicherpfad |

Fehlerantworten werden zu `Error(detail ?? message ?? status)`. Das Plugin mischt die `qdnl`-Texte
in die vue-i18n-Instanz des Hosts und wirft, wenn keine vorhanden ist.

`parseSse(onEvent)` gibt eine Funktion `push(chunk): boolean` zurück und verhält sich wie der Parser
des Chats.

## `QdNlChartPrompt`

### Props, Events, Slots

| Art | Name | Bedeutung |
|---|---|---|
| Prop | `initialParams?` | aktuelle `ChartParams` (z. B. aus dem Chart-Builder); die erste Frage verfeinert dann diese Params |
| Prop | `datasetId?` | Dataset fest vorgeben |
| Prop | `dashboardId?` | beim Speichern an dieses Dashboard anhängen (`dashboards: [id]`) |
| Event | `saved(chart)` | Chart wurde über die Dashboard-API gespeichert (Antwort der API) |
| Event | `open-builder({datasetId, vizType, params})` | Host soll den Chart-Builder öffnen und befüllen |
| Slot | `empty` | Inhalt, solange es keinen Vorschlag gibt |
| Slot | `actions` (`{suggestion}`) | zusätzliche Aktionen neben Speichern/Builder/Verwerfen |

`data-testid`: `qdnl-prompt`, `qdnl-question`, `qdnl-ask`, `qdnl-error`, `qdnl-suggestion`,
`qdnl-followup`, `qdnl-save`, `qdnl-open-builder`.

### Ablauf

```
Frage (Strg/⌘+Enter oder Button)
 └─ ask(text, current)
     ├─ Body: question, dataset_id (gewählt oder vom vorherigen Vorschlag bei Verfeinerung),
     │        current_params (Verfeinerung), dashboard_id
     ├─ step-Events → Fortschrittsliste
     ├─ chart_suggestion → suggestion; Name (≤ 250 Zeichen) und Beschreibung vorbelegt
     └─ error → Fehlerbanner
Vorschau
 ├─ mode "semantic": QdChartRenderer im params-Modus (lädt Daten selbst über die Dashboard-API)
 │     previewParams = Plugin-Defaults ∪ params ∪ {viz_type, datasource: "<id>__table"}
 └─ mode "sql":      QdChartRenderer mit :results (vom Backend, status "success"), Hinweis „nur Ansicht“,
                     SQL anzeigbar; ohne results (leeres Ergebnis) keine Vorschau
Anzeige: explanation, assumptions, warnings, Varianten-Chips aus alternatives
Folgefrage → ask(followUp, {...suggestion.params, viz_type})        (Diff statt Neugenerierung)
Variante   → ask(t('qdnl.asVariant', {question, vizType}), {...params, viz_type})   ("… — as bar" / "… — als bar")
Speichern  → nur mode "semantic": saveChart({slice_name, description, viz_type, datasource_id,
             datasource_type: "table", params, query_context, dashboards?}) → Event saved
             lehnt die Dashboard-API ab (z. B. 422), erscheint die Meldung im Fehlerbanner
Builder    → Event open-builder
Verwerfen  → suggestion = null
```

### Chart-Builder befüllen (Aufgabe des Hosts)

Die Komponente kennt keine Routen und keinen Store. Der Host setzt das Event um (FR-NLC-17), in der
Demo-App über `open-in-builder.ts`:

```ts
const explore = useExploreStore()
await explore.init({ datasetId, vizType })
for (const [name, value] of Object.entries(params))
  if (name !== 'viz_type' && name !== 'datasource') explore.setControl(name, value)
await explore.run()
// danach zur Explore-Seite navigieren
```

## Einbindung in quasar-dashboard-Seiten (Slots)

| Einstieg (FR-NLC-18) | Slot | Stand |
|---|---|---|
| „Mit Frage anpassen“ im Chart-Builder | `QdChartBuilder` → `header-actions` | umgesetzt in `apps/demo/src/pages/ExplorePage.vue` |
| „Chart per Frage hinzufügen“ im Dashboard (Edit-Modus) | `QdDashboard` → `header-actions` | fehlt |
| „Per Frage abwandeln“ in der Chart-Liste | `QdChartList` → `row-actions` | fehlt |

Muster für einen Dashboard-Dialog:

```vue
<QdNlChartPrompt :dashboard-id="dashboard.id" @saved="reloadDashboard" />
```

## Texte

`src/i18n/index.ts` enthält `en` und `de` (Typ `typeof en`) im Namespace `qdnl`. Der Test prüft
zusätzlich, dass beide Sprachen dieselben Schlüssel haben.

## Tests

`test/sse.test.ts`: `parseSse` über Chunk-Grenzen, gleiche i18n-Schlüssel in DE und EN.

```bash
pnpm --filter @jentsch/quasar-dashboard-nl test    # oder im Wurzelverzeichnis: pnpm test (beide Bibliotheken)
```

## Code-Beispiele

### Einbinden

```ts
// boot/plugins.ts – nach Pinia, vue-i18n und createQuasarDashboard
app.use(createQuasarDashboardNl({
  baseUrl: import.meta.env.VITE_API_BASE_URL ?? '',
  apiPrefix: '/api/nl-chart',            // = ao.nl-chart.api-prefix
  dashboardApiPrefix: '/api/v1',         // = quasar-dashboard.api-prefix (zum Speichern)
}))
```

```vue
<QdNlChartPrompt
  :dataset-id="datasetId"
  :dashboard-id="dashboardId"
  :initial-params="currentParams"
  @saved="(chart) => Notify.create({ type: 'positive', message: t('qdnl.saved') })"
  @open-builder="({ datasetId, vizType, params }) => openInBuilder({ datasetId, vizType, params })"
/>
```

### Den Client ohne Komponente verwenden

```ts
const nl = createNlClient({ apiPrefix: '/api/nl-chart' })
let suggestion: Record<string, unknown> | null = null
await nl.suggest({ question: 'Revenue by region last year', dataset_id: 1 }, (e) => {
  if (e.type === 'chart_suggestion') suggestion = e
  else if (e.type === 'error') throw new Error(String(e.message))
})
if (suggestion?.mode === 'semantic') {
  await nl.saveChart({
    slice_name: suggestion.slice_name, viz_type: suggestion.viz_type,
    datasource_id: suggestion.dataset_id, datasource_type: 'table',
    params: suggestion.params, query_context: suggestion.query_context ?? null,
  })
}
```

Nach einem `error`-Event folgt **kein** `chart_suggestion` mehr: Scheitert die Validierung im
Backend endgültig, endet der Stream mit `error` und `[DONE]`.

## Fallstricke

- **Kein `plugin.validate()`:** Die Vorschau ergänzt nur die Plugin-Defaults; die clientseitige
  Validierung aus FR-NLC-13 und das Blockieren des Speicherns bei Fehlern fehlen.
- **Abbrechen** ist nicht vorgesehen: `suggest` akzeptiert ein `AbortSignal`, die Komponente übergibt
  aber keins. (Nach `[DONE]` wird der Reader geschlossen und die Verbindung freigegeben.)
- **Mock-Modus fehlt:** Es gibt keine MSW-Handler für `/api/nl-chart/**` (FR-NLC-20); die Komponente
  braucht ein laufendes `ao-dashboard-demo`-Backend.
- `datasource` wird als `"<id>__table"` gebildet – das Format muss zu quasar-dashboard passen.

## Anforderungsbezug

FR-NLC-12, FR-NLC-15 bis FR-NLC-19, FR-NLC-21 (über die Demo-App), NFR-QD-06a, NFR-QD-08.
