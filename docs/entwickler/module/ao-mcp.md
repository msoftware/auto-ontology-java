# Modul `ao-mcp`

Paket `de.jentsch.autoontology.mcp` · [Zurück zur Übersicht](../README.md)

## Zweck

Ein **read-only MCP-Server** (Model Context Protocol, Transport Streamable HTTP) unter dem festen
Pfad `/mcp`. Externe Agenten (Claude, IDEs, eigene Agenten) können damit die Ontologie
durchsuchen, Beantwortbarkeit prüfen und Fragen stellen. Vorbild: `auto-ontology/mcp/auto_ontology_mcp/`.

Der Server ist bewusst **ein HTTP-Client der eigenen öffentlichen API**: Er hat keinen Zugriff auf
Store, Konnektoren oder Credentials und kann nur, was die API ohnehin anbietet.

## Abhängigkeiten

`api`: `ao-common`, `spring-boot-starter-webmvc`. `implementation`:
`io.modelcontextprotocol.sdk:mcp` 2.1.0 (MCP Java SDK). Keine Abhängigkeit zu den Fachmodulen.

## Klassen

| Klasse | Aufgabe |
|---|---|
| `McpAutoConfiguration` | Transport, Servlet-Registrierung, `McpTools`, `McpSyncServer` |
| `McpTools` | Tool-Definitionen (Name, Beschreibung, JSON-Schema), Server-Instructions, Aufruf der API per `java.net.http.HttpClient` |

## Beans

| Bean | Typ | Zweck |
|---|---|---|
| `aoMcpTransport` | `HttpServletStreamableServerTransportProvider` | Streamable-HTTP-Transport mit Jackson-3-Mapper, Endpunkt `/mcp` |
| `aoMcpServlet` | `ServletRegistrationBean` | registriert den Transport auf `/mcp` und `/mcp/*`, `asyncSupported = true` |
| `aoMcpTools` | `McpTools` | API-Client mit Basis-URL |
| `aoMcpServer` | `McpSyncServer` | Server `auto-ontology` 0.1.0 mit Instructions und allen Tools |

Die ganze Auto-Konfiguration ist an `ao.mcp.enabled` gebunden (Default `true`).

## Tools

| Tool | API-Aufruf |
|---|---|
| `check_readiness` | Kompilierungsstatus, Verbindungen und Datenbanken → ein Urteil |
| `search_terms` | `GET /terms` |
| `get_term` | `GET /terms/{term_id}` |
| `get_term_columns` | `GET /terms/{term_id}/column-attributes` |
| `get_term_sql_attributes` | `GET /terms/{term_id}/sql-attributes` |
| `get_sql_attribute` | `GET /sql-attributes/{attr_id}` |
| `describe_table` | `GET /exploration/tables/{table_id}/details` |
| `check_answerable` | `POST /question-entity-coverage` |
| `ask_question` | `POST /chat/completions`, Stream bis `[DONE]` lesen, Antwort, SQL und bis zu 100 Zeilen zurückgeben |

Die Tool-Beschreibungen und `INSTRUCTIONS` sind **wörtlich** aus `tools.py` / `server.py` des
Originals übernommen; sie steuern, wie fremde Agenten die Tools einsetzen. Nicht frei umformulieren.

## Implementierungsdetails

- **Basis-URL:** `ao.mcp.api-base-url`, wenn gesetzt. Sonst ein `Supplier`, der zur Aufrufzeit
  `http://localhost:<local.server.port|server.port|8080><ao.api-prefix>` bildet – so funktioniert auch
  ein zufälliger Testport, und das Präfix ist nie hart kodiert.
- **HTTP:** Connect-Timeout 10 s, Request-Timeout 60 s, für `ask_question` 900 s. Jeder Aufruf
  sendet `x-auto-ontology-source: mcp`, damit MCP-Fragen in den Analytics getrennt erscheinen.
- **Ergebnis:** Jedes Tool-Ergebnis wird als JSON-Text (`addTextContent`) zurückgegeben. Eine
  `RuntimeException` (z. B. Backend nicht erreichbar, HTTP-Fehler) wird zu einem Ergebnis mit
  `isError = true` und der Fehlermeldung.
- **Keine Anmeldung:** Der Transport prüft keine Tokens; Aufrufe laufen als fester Benutzer.
  Schutz ist allein der Betrieb im internen Netz.

## Konfiguration

| Property | Default | Wirkung |
|---|---|---|
| `ao.mcp.enabled` | `true` | Server an/aus |
| `ao.mcp.api-base-url` | eigener Prozess | Basis-URL **inklusive** Präfix, z. B. `http://ao-api:8080/api` |

Client-Konfiguration (Beispiel):

```json
{ "mcpServers": { "auto-ontology": { "type": "http", "url": "http://localhost:8080/mcp" } } }
```

## Erweiterungspunkte

**Neues Tool** (z. B. `suggest_chart`, FR-NLC-22a):

1. In `McpTools.TOOLS` einen `Tool(name, description, inputSchema)` ergänzen (Schema mit
   `schema(props, required)`).
2. In `McpTools.call` den Fall auf einen API-Aufruf abbilden.
3. Nur lesende Endpunkte verwenden – „MCP ist read-only“ ist die wichtigste Schutzgrenze ohne
   Anmeldung.
4. `McpServerTest` erweitern.

## Tests

`McpServerTest` (`ao-app`): startet die Anwendung auf einem Zufallsport, initialisiert eine
MCP-Sitzung, listet die Tools und ruft eines auf.

## Fallstricke

- Ohne `ao.mcp.api-base-url` geht der Aufruf an `localhost`. Bindet der Server nur an eine andere
  Schnittstelle oder läuft hinter einem Reverse Proxy mit anderem Präfix, die URL setzen.
- Der MCP-Server erbt die Grenzen der API: `ask_question` blockiert bis zu 15 Minuten.
- Weil der Server über HTTP auf sich selbst zugreift, belegt ein Tool-Aufruf zwei Requests im
  selben Prozess (MCP-Request und API-Request).

## Anforderungsbezug

FR-MCP-01, FR-MCP-02, FR-CHAT-01 (Quelle `mcp`), NFR-ARC-05.
