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
- **`ask_question` liest einen SSE-Stream.** Der Zeilen-Stream der HTTP-Antwort wird in einem
  `try`-with-resources geschlossen – auch wenn die Schleife bei `[DONE]` oder mit einem Fehler
  endet. Sonst bliebe die Verbindung zum eigenen Backend offen.
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

## Code-Beispiele

### Ein neues Tool

```java
// McpTools.TOOLS – Name, Beschreibung (steuert, wann ein Agent das Tool wählt) und Eingabeschema
new Tool("list_custom_analyses", "List the reviewed custom analyses (named SQL queries) of this deployment. "
        + "Use it to find an existing, trusted query before asking a new question.",
        schema(Map.of("query", "Optional search text"), List.of())),
```

```java
// McpTools.call – auf einen LESENDEN API-Aufruf abbilden
case "list_custom_analyses" -> get("/custom-analyses?limit=50&query=" + enc(a.getOrDefault("query", "")));
```

Werte aus den Tool-Argumenten immer mit `enc(...)` in die URL setzen. `POST`-Tools sind nur für
Endpunkte erlaubt, die nichts verändern (`/question-entity-coverage`, `/chat/completions`).

### Die Tools direkt aufrufen (Test oder Skript)

```java
McpTools tools = new McpTools("http://localhost:8080/api");      // Basis-URL inklusive Präfix
Object terms = tools.call("search_terms", Map.of("query", "customer"));
Map<String, Object> ready = (Map<String, Object>) tools.call("check_readiness", Map.of());
// {ready=false, catalog_ingested=true, has_connection=true, semantic_layer_compiled=false,
//  blockers=[The semantic layer has not been compiled: enable the semantic compilation.], …}

Map<String, Object> answer = (Map<String, Object>) tools.call("ask_question",
        Map.of("question", "How many wholesale customers are there?"));
// {answer=…, sql=SELECT COUNT(*) …, rows=[{COUNT=42}], row_count=1, truncated=false, steps=[…]}
```

### Über das Protokoll

```bash
# Streamable HTTP: initialisieren, dann Tools listen bzw. aufrufen (Session-ID aus dem Header der ersten Antwort)
curl -si -X POST localhost:8080/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'
curl -s -X POST localhost:8080/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
     -H 'Mcp-Session-Id: <id>' \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_terms","arguments":{"query":"customer"}}}'
```

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
