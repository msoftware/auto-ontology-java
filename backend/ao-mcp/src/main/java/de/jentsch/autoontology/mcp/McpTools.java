// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.mcp;

import de.jentsch.autoontology.common.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read-only MCP tools (FR-MCP-01). The MCP server is an HTTP client of the own public API and
 * holds no database credentials; the base URL including the prefix comes from
 * {@code ao.mcp.api-base-url}. Tool descriptions are taken verbatim from
 * mcp/auto_ontology_mcp/tools.py, chat.py and readiness.py of NVIDIA Auto-Ontology.
 */
public class McpTools {
    static final int MAX_ROWS = 100;

    /** A tool: name, verbatim description, JSON input schema. */
    public record Tool(String name, String description, Map<String, Object> inputSchema) {}

    static final String INSTRUCTIONS = """
            Auto Ontology answers questions about an organisation's
            structured data. It holds a compiled semantic layer — a glossary of business
            terms mapped onto real database columns and reviewed SQL expressions — over the
            databases this deployment is connected to.

            Use `ask_question` for anything that needs an actual answer from the data. It
            runs a full text-to-SQL agent and returns the answer, the SQL it ran, and the
            rows.

            The other tools exist so you can understand the vocabulary before you ask, and
            check your assumptions after. A productive sequence is usually:

            1. `search_terms` to find out what a business word means here. Deployments
               differ: "active customer" is a defined term with specific SQL behind it,
               not something to guess at.
            2. `check_answerable` if you are unsure the question is in scope. It is far
               cheaper than `ask_question` and tells you whether the semantic layer covers
               the entities involved.
            3. `ask_question` to get the answer.

            `check_readiness` answers a different question: whether this deployment can
            answer anything at all. A deployment with no database connection reads normally
            and still fails every question, so reach for it before a first question here, or
            when `ask_question` comes back empty — an empty answer is far more often a
            deployment that is not set up than a question that was misunderstood.

            Work through the semantic layer, not around it. `get_term_columns` tells you
            which physical columns a term stands for, `get_term_sql_attributes` and
            `get_sql_attribute` give you expressions that were already reviewed here, and
            `describe_table` says what a table means rather than only its shape. There is
            deliberately no tool for browsing databases, schemas, or raw columns:
            questions are answered against the glossary.
            """;

    private static Map<String, Object> schema(Map<String, String> props, List<String> required) {
        Map<String, Object> p = new LinkedHashMap<>();
        props.forEach((k, d) -> p.put(k, Map.of("type", "string", "description", d)));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", p);
        s.put("required", required);
        return s;
    }

    public static final List<Tool> TOOLS = List.of(
            new Tool("check_readiness", "Report whether this Auto Ontology deployment can answer questions at all: "
                    + "whether a catalog has been ingested, whether a live database "
                    + "connection exists to run SQL against, and whether the semantic "
                    + "layer has been compiled. All three are required.\n\n"
                    + "Use it before the first question against an unfamiliar "
                    + "deployment, and whenever ask_question returns an empty answer. It is "
                    + "three cheap reads, and it separates 'this deployment is not set "
                    + "up' from 'the question was not understood' — which otherwise look "
                    + "identical and are fixed in completely different places.\n\n"
                    + "Facts the signed-in account may not read come back under "
                    + "'unverified' rather than as blockers, so a ready verdict with "
                    + "unverified entries still means questions can be asked.", schema(Map.of(), List.of())),
            new Tool("search_terms", "Search the business glossary. Terms are the human-meaningful "
                    + "concepts Auto Ontology has compiled over the connected databases — "
                    + "\"Customer\", \"Open Order\", \"Net Revenue\" — each mapped to real "
                    + "columns and SQL expressions. Use this first to learn what a "
                    + "business word actually means in this deployment before asking a "
                    + "question that depends on it.", schema(Map.of("query", "Search text"), List.of())),
            new Tool("get_term", "Fetch one glossary term: its description, synonyms, and the "
                    + "terms related to it. Use after search_terms to confirm a term "
                    + "means what you assumed.", schema(Map.of("term_id", "Term id"), List.of("term_id"))),
            new Tool("get_term_columns", "List the physical table columns a glossary term maps to. This is "
                    + "how you get from a business concept to the data behind it.",
                    schema(Map.of("term_id", "Term id"), List.of("term_id"))),
            new Tool("get_term_sql_attributes", "List the SQL attributes defined under a glossary term — reviewed "
                    + "SQL expressions such as a margin, a ratio, or a rolling total. "
                    + "Prefer reusing one of these over inventing the arithmetic "
                    + "yourself.", schema(Map.of("term_id", "Term id"), List.of("term_id"))),
            new Tool("get_sql_attribute", "Fetch one SQL attribute: its expression, the term it belongs to, "
                    + "and what it is for.", schema(Map.of("attr_id", "SQL attribute id"), List.of("attr_id"))),
            new Tool("describe_table", "Describe one table in full: its columns, the glossary terms that "
                    + "reference it, and the SQL attributes defined over it. The richest "
                    + "single view of what a table is for.", schema(Map.of("table_id", "Table id"), List.of("table_id"))),
            new Tool("check_answerable", "Grade how well the semantic layer covers the entities in a "
                    + "question, without running the full agent. A cheap pre-flight: "
                    + "use it to decide whether Auto Ontology can answer something before paying "
                    + "for ask_question, which is far slower and more expensive.",
                    schema(Map.of("question", "The question"), List.of("question"))),
            new Tool("ask_question", "Ask a natural-language question about the data connected to this "
                    + "Auto Ontology deployment. Auto Ontology resolves the question against its semantic "
                    + "layer, writes SQL, runs it, and returns the answer together with "
                    + "the SQL it used.\n\n"
                    + "This is the primary tool and the reason Auto Ontology exists. It is also "
                    + "slow — many sequential model calls, typically tens of seconds — "
                    + "so use check_answerable first when you are unsure the question is "
                    + "in scope, and prefer one well-formed question over several "
                    + "narrow ones.", schema(Map.of("question", "The question, in plain language.",
                            "conversation_id", "Continue an existing thread, so follow-up questions resolve against"
                                    + " earlier turns.",
                            "evidence", "Optional authoritative evidence supplied separately from the question."),
                            List.of("question"))));

    private final java.util.function.Supplier<String> base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public McpTools(String baseUrl) {
        this(() -> baseUrl);
    }

    /** The base URL is resolved per call (the local port is only known after start). */
    public McpTools(java.util.function.Supplier<String> base) {
        this.base = base;
    }

    private String baseUrl() {
        String b = base.get();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static String enc(Object v) {
        return URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8);
    }

    private Object get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET());
    }

    private Object post(String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))));
    }

    private Object send(HttpRequest.Builder b) {
        try {
            HttpResponse<String> r = http.send(b.header("x-auto-ontology-source", "mcp").timeout(Duration.ofSeconds(60))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 400) {
                throw new IllegalStateException("Auto Ontology API returned " + r.statusCode() + ": " + r.body());
            }
            return Json.readAny(r.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Auto Ontology backend is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** Runs one tool; read-only by construction (FR-MCP-02). */
    @SuppressWarnings("unchecked")
    public Object call(String tool, Map<String, Object> args) {
        Map<String, Object> a = args == null ? Map.of() : args;
        return switch (tool) {
            case "check_readiness" -> readiness();
            case "search_terms" -> get("/terms?limit=50&query=" + enc(a.getOrDefault("query", "")));
            case "get_term" -> get("/terms/" + enc(a.get("term_id")));
            case "get_term_columns" -> get("/terms/" + enc(a.get("term_id")) + "/column-attributes");
            case "get_term_sql_attributes" -> get("/terms/" + enc(a.get("term_id")) + "/sql-attributes");
            case "get_sql_attribute" -> get("/sql-attributes/" + enc(a.get("attr_id")));
            case "describe_table" -> get("/exploration/tables/" + enc(a.get("table_id")) + "/details");
            case "check_answerable" -> post("/question-entity-coverage", Map.of("question", a.getOrDefault("question", "")));
            case "ask_question" -> ask(a);
            default -> throw new IllegalArgumentException("Unknown tool " + tool);
        };
    }

    /** readiness.py: catalog ingested, live connection, semantic layer compiled. */
    @SuppressWarnings("unchecked")
    Map<String, Object> readiness() {
        Map<String, Object> status = (Map<String, Object>) get("/semantic-compilation/status");
        Map<String, Object> connections = (Map<String, Object>) get("/connections");
        Map<String, Object> dbs = (Map<String, Object>) get("/datasources/dbs");
        boolean catalog = ((Number) dbs.getOrDefault("count", 0)).intValue() > 0;
        boolean connection = ((Number) connections.getOrDefault("count", 0)).intValue() > 0;
        boolean compiled = Boolean.TRUE.equals(status.get("calculated"));
        List<String> blockers = new ArrayList<>();
        if (!catalog) {
            blockers.add("No catalog has been ingested yet: add a database connection.");
        }
        if (!connection) {
            blockers.add("No live database connection exists to run SQL against.");
        }
        if (!compiled) {
            blockers.add("The semantic layer has not been compiled: enable the semantic compilation.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ready", blockers.isEmpty());
        out.put("catalog_ingested", catalog);
        out.put("has_connection", connection);
        out.put("semantic_layer_compiled", compiled);
        out.put("compilation_running", status.get("running"));
        out.put("blockers", blockers);
        out.put("unverified", List.of());
        return out;
    }

    /** chat.py: streams the chat API and folds it into answer, SQL, rows, steps. */
    @SuppressWarnings("unchecked")
    Map<String, Object> ask(Map<String, Object> a) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", a.get("question"));
        if (a.get("conversation_id") != null) {
            body.put("conversation_id", a.get("conversation_id"));
        }
        if (a.get("evidence") != null) {
            body.put("evidence", a.get("evidence"));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .header("x-auto-ontology-source", "mcp").timeout(Duration.ofSeconds(900))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        List<String> steps = new ArrayList<>();
        Map<String, Object> result = null;
        String error = null;
        try {
            HttpResponse<java.util.stream.Stream<String>> r = http.send(req, HttpResponse.BodyHandlers.ofLines());
            if (r.statusCode() >= 400) {
                throw new IllegalStateException("Auto Ontology API returned " + r.statusCode());
            }
            for (String line : (Iterable<String>) r.body()::iterator) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).strip();
                if (payload.equals("[DONE]")) {
                    break;
                }
                Map<String, Object> e = Json.readMap(payload);
                switch (String.valueOf(e.get("type"))) {
                    case "step" -> {
                        if ("end".equals(e.get("phase"))) {
                            steps.add(e.get("label") + (e.get("thought") == null ? "" : ": " + e.get("thought")));
                        }
                    }
                    case "result" -> result = (Map<String, Object>) e.get("answer");
                    case "error" -> error = String.valueOf(e.get("message"));
                    default -> { }
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Auto Ontology backend is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        if (result == null) {
            throw new IllegalStateException(error == null ? "The agent returned no answer" : error);
        }
        List<Object> rows = result.get("sql_response_from_db") instanceof List<?> l ? new ArrayList<>(l) : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", result.get("response"));
        out.put("sql", result.getOrDefault("sql_code", ""));
        out.put("rows", rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows);
        out.put("row_count", rows.size());
        out.put("truncated", rows.size() > MAX_ROWS);
        out.put("steps", steps);
        return out;
    }
}
