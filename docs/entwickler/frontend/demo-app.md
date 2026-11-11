# Demo-App `ao-demo`

Verzeichnis `frontend/apps/demo` · [Zurück zur Frontend-Übersicht](README.md)

## Zweck

Eine kleine Quasar-App, die zeigt, wie ein **Host** die Bibliotheken einbindet: Plugins
installieren, Seiten auf Routen legen, Navigation und Header bauen, Events der Bibliotheken in
Navigation übersetzen. Sie ist zugleich das Frontend des Docker-Images (statische Dateien in Nginx).

## Abhängigkeiten

`dependencies`: `@jentsch/quasar-dashboard`, `@jentsch/quasar-ontology`,
`@jentsch/quasar-dashboard-nl` (alle `workspace:*`), `quasar`, `@quasar/extras`, `vue`, `vue-router`,
`vue-i18n`, `pinia`, `echarts`, `vue-echarts`, `grid-layout-plus`, `markdown-it`, `dompurify`
(Letztere drei für quasar-dashboard).
`devDependencies`: `@quasar/app-vite`, `@jentsch/mock-backend` (derzeit ungenutzt), `typescript`,
`vue-tsc`, `sass-embedded`, `postcss`, `autoprefixer`.

## Dateien

| Datei | Inhalt |
|---|---|
| `quasar.config.ts` | Boot-Dateien `i18n`, `plugins`; History-Router; Aliase auf die Quellen von quasar-dashboard; Dedupe; Dev-Server Port 9100 mit Proxy; Quasar `dark: 'auto'`, Plugins `Notify`, `Dialog`, `Dark` |
| `.env.development` | `VITE_AO_API_PREFIX=/api` |
| `src/boot/i18n.ts` | vue-i18n (`legacy: false`), Sprache aus dem Browser, Fallback `en`, Texte `app.*` |
| `src/boot/plugins.ts` | Pinia, `createQuasarDashboard`, `createQuasarOntology`, `createQuasarDashboardNl` mit gemeinsamen Optionen |
| `src/router/index.ts`, `routes.ts` | Routen |
| `src/layouts/MainLayout.vue` | Header (Titel, Slot `header-actions`, DE/EN, Dark Mode), linke Navigation |
| `src/pages/*.vue` | dünne Hüllen um die Bibliotheksseiten |
| `src/open-in-builder.ts` | befüllt den Explore-Store von quasar-dashboard aus einem NL-Vorschlag |
| `src/css/app.scss` | minimale globale Styles |

Die Boot-Reihenfolge ist wichtig: `i18n` vor `plugins`, weil die Bibliotheken ihre Texte in die
vorhandene vue-i18n-Instanz mischen. Pinia wird vor `createQuasarDashboard` installiert, weil die
Stores von quasar-dashboard (Renderer, Chart-Builder) sie brauchen.

## Routen

| Pfad | Seite | Hülle |
|---|---|---|
| `/` | Redirect auf `/chat` | – |
| `/chat` | `AoChatPage` | `ChatPage.vue`: lädt Vorschläge aus `/api/demo/questions` (nur Demo-Profil, sonst leer), spiegelt `focus` in `?focus=<id>`, leitet `entity` auf `/terms?id=<id>` |
| `/terms` | `AoTermsPage` | `TermsPage.vue`: `?id=` → Prop `termId` |
| `/discovery`, `/analysis`, `/exploration`, `/data`, `/analytics` | jeweilige Ao-Seite | `LibPage.vue` mit Prop `page` |
| `/settings/:section?` | `AoSettingsPage` | `SettingsPage.vue`: Param ↔ Event `section` |
| `/ask` | `QdNlChartPrompt` | `AskPage.vue`: `?params=<json>` als `initialParams`; `saved` → Notify; `open-builder` → `openInBuilder` + `/explore` |
| `/explore` | `QdChartBuilder` (quasar-dashboard) | `ExplorePage.vue`: Button „Mit Frage anpassen“ im Slot `header-actions`, Dialog mit `QdNlChartPrompt` |

Alle Seiten liegen unter `MainLayout` und werden per Lazy-Import geladen.

## Konfiguration

| Variable | Wo | Wirkung | Default |
|---|---|---|---|
| `AO_BACKEND` | Prozess-Umgebung beim `pnpm dev` | Ziel des Dev-Proxys | `http://localhost:8080` |
| `VITE_AO_API_PREFIX` | `.env*` oder Umgebung | Proxy-Pfad und `apiPrefix` der Plugins (NL: `<prefix>/nl-chart`) | `/api` |
| `VITE_API_BASE_URL` | `.env*` oder Umgebung | `baseUrl` aller Plugins | leer (gleiche Origin) |

Der Proxy leitet nur den Pfad `VITE_AO_API_PREFIX` weiter. Liegen Dashboard-API (`/api/v1`) und
NL-API unter diesem Präfix, funktionieren sie mit; bei abweichenden Präfixen weitere Proxy-Einträge
in `quasar.config.ts` ergänzen. `/mcp` wird im Dev-Server nicht weitergeleitet (wird im Browser auch
nicht gebraucht).

`VITE_*`-Variablen werden beim **Build** in das Bundle geschrieben; für ein anderes Präfix im
Docker-Image muss neu gebaut werden.

## Build und Auslieferung

```bash
pnpm build      # = pnpm --filter ao-demo build → apps/demo/dist/spa
```

`docker/frontend.Dockerfile` kopiert `dist/spa` in `nginx:1.29-alpine`; `docker/nginx.conf`

- liefert die SPA mit History-Fallback (`try_files … /index.html`),
- leitet `/api/` und `/mcp` an `backend:8080` weiter, mit `proxy_buffering off` und 900 s
  Read-Timeout (SSE),
- setzt eine Content-Security-Policy (`default-src 'self'`, Google Fonts erlaubt,
  `connect-src 'self'`).

Wegen `connect-src 'self'` muss das Backend über denselben Origin erreichbar sein; ein
`VITE_API_BASE_URL` auf einen anderen Host würde von der CSP blockiert.

## Als Vorlage für einen eigenen Host

1. `@jentsch/quasar-ontology` (und optional `@jentsch/quasar-dashboard-nl`) mit ihren
   Peer-Dependencies einbinden.
2. vue-i18n (`legacy: false`) und Pinia installieren, dann die Plugins mit gemeinsamen Optionen.
3. Seiten auf eigene Routen legen; Events (`focus`, `entity`, `section`, `saved`, `open-builder`)
   in Navigation übersetzen.
4. Navigation/Header selbst bauen; das Layout gehört nicht zur Bibliothek.
5. Dedupe von `vue`, `quasar`, `vue-i18n`, `pinia`, `echarts` sicherstellen, falls quasar-dashboard
   aus den Quellen kommt.
6. Reverse Proxy für SSE konfigurieren (kein Puffern, langes Timeout).

## Fallstricke

- Die Demo-Fragen (`/api/demo/questions`) gibt es nur im Backend-Profil `demo`; sonst bleibt die
  Vorschlagsliste leer (der Fehler wird verschluckt).
- `AskPage` parst `?params=` ohne Fehlerbehandlung; ungültiges JSON in der URL bricht die Seite.
- Die Seitenhöhe ist fest als `calc(100vh - 50px)` gesetzt (Header-Höhe).
- `@jentsch/mock-backend` ist installiert, aber es gibt keinen `VITE_MOCK_API`-Modus.
- Die Menüeinträge sind in `MainLayout.vue` hart verdrahtet; neue Bibliotheksseiten müssen dort
  ergänzt werden.

## Anforderungsbezug

FR-FE-01, FR-FE-02, FR-FE-05, FR-NLC-17, FR-NLC-18 (teilweise), FR-NLC-21, NFR-QD-06a, NFR-SEC-05,
NFR-DEP-01.
