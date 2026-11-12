// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.semantic;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CancellationToken;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.ingestion.EmbeddingTexts;
import de.jentsch.autoontology.ingestion.PiiDetection;
import de.jentsch.autoontology.llm.Embedder;
import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.llm.LlmMessage;
import de.jentsch.autoontology.llm.ModelRole;
import de.jentsch.autoontology.llm.Prompts;
import de.jentsch.autoontology.llm.RerankClient;
import de.jentsch.autoontology.semantic.Profiler.ColumnProfile;
import de.jentsch.autoontology.semantic.SemanticModels.BridgeSqlAttributeProposal;
import de.jentsch.autoontology.semantic.SemanticModels.FkHitSelection;
import de.jentsch.autoontology.semantic.SemanticModels.PotentialFkSuggestion;
import de.jentsch.autoontology.semantic.SemanticModels.SemanticFkJoinDefaultSelection;
import de.jentsch.autoontology.semantic.SemanticModels.SuggestedAttr;
import de.jentsch.autoontology.semantic.SemanticModels.Suggestions;
import de.jentsch.autoontology.semantic.TableTaxonomy.FkResult;
import de.jentsch.autoontology.semantic.TableTaxonomy.Spec;
import de.jentsch.autoontology.semantic.TableTaxonomy.TermProposal;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.vector.VectorStore;
import de.jentsch.autoontology.vector.VectorStore.SearchHit;
import de.jentsch.autoontology.vector.VectorStore.VectorFilter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Semantic compilation of one database (semantic/compile.py, pipeline.py; FR-SEM-01..06): stage 1
 * taxonomy per table in parallel with canonical commit order, stage 2 semantic FKs, stage 3 SQL
 * attributes from the query history, stage 4 bridge tables. Cancellation is cooperative.
 */
public class SemanticCompiler {
    private static final Logger log = LoggerFactory.getLogger(SemanticCompiler.class);
    private static final int VDB_TOP_K = 5;
    private static final int SQL_EXPRESSIONS_TOP = 10;

    private final CatalogRepository catalog;
    private final SemanticRepository semantic;
    private final LlmGateway llm;
    private final Embedder embedder;
    private final VectorStore vectors;
    private final SemanticEmbedder semanticEmbedder;
    private final RerankClient rerank;
    private final RuntimeSettings settings;
    private final AoProperties props;
    private final AoTime time;
    private final PiiDetection pii;
    private final Profiler profiler = new Profiler();
    private final TableTaxonomy taxonomy;

    public SemanticCompiler(CatalogRepository catalog, SemanticRepository semantic, LlmGateway llm, Embedder embedder,
            VectorStore vectors, SemanticEmbedder semanticEmbedder, RerankClient rerank, RuntimeSettings settings,
            AoProperties props, AoTime time, PiiDetection pii) {
        this.catalog = catalog;
        this.semantic = semantic;
        this.llm = llm;
        this.embedder = embedder;
        this.vectors = vectors;
        this.semanticEmbedder = semanticEmbedder;
        this.rerank = rerank;
        this.settings = settings;
        this.props = props;
        this.time = time;
        this.pii = pii;
        this.taxonomy = new TableTaxonomy(llm);
    }

    /** Summary of a run (logged as one line, FR-SEM-01). */
    public record Summary(String database, int tables, int terms, int semanticFks, int sqlAttributes, int bridges,
            long millis) {
        @Override
        public String toString() {
            return "Semantic compilation " + database + ": " + tables + " tables, " + terms + " terms, " + semanticFks
                    + " semantic FKs, " + sqlAttributes + " SQL attributes, " + bridges + " bridges in " + millis + " ms";
        }
    }

    public Summary compile(String databaseId, String databaseName, SourceDatabase db, CancellationToken cancel) {
        long start = System.nanoTime();
        profiler.resetBreaker(databaseName);
        int tables = stageTaxonomy(databaseId, databaseName, db, cancel);
        cancel.check();
        int fks = stageSemanticFks(databaseId, databaseName, db);
        cancel.check();
        int sqlAttrs = stageSqlAttributes(databaseId, databaseName);
        cancel.check();
        int bridges = stageBridges(databaseId, databaseName);
        int terms = (int) semantic.countTerms();
        Summary s = new Summary(databaseName, tables, terms, fks, sqlAttrs, bridges, (System.nanoTime() - start) / 1_000_000);
        log.info("{}", s);
        return s;
    }

    // ================================================================== stage 1: taxonomy

    /** Result of processing one table, committed later in canonical order. */
    private record TableWork(Map<String, Object> table, List<TermProposal> terms, List<Spec> specs, boolean junction) {}

    private int stageTaxonomy(String databaseId, String databaseName, SourceDatabase db, CancellationToken cancel) {
        List<Map<String, Object>> tables = catalog.tablesWithoutTerm(databaseId);
        if (tables.isEmpty()) {
            return 0;
        }
        List<Map<String, Object>> allFks = catalog.foreignKeys(databaseId);
        Set<String> fkTargets = allFks.stream().map(f -> f.get("target_table_id") + "." + f.get("target_column"))
                .collect(Collectors.toSet());
        Semaphore permits = new Semaphore(Math.max(1, props.semantic().llmMaxInflight()));
        boolean probing = settings.distinctValueProbingEnabled();
        String domain = DomainContext.block(databaseName);
        Set<String> piiColumns = pii.piiColumnIds();
        int done = 0;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TableWork>> futures = new ArrayList<>();
            for (Map<String, Object> t : tables) {
                futures.add(pool.submit(() -> {
                    // checked before every table; running tables finish (FR-SEM-06)
                    if (cancel.isCancelled()) {
                        return null;
                    }
                    permits.acquire();
                    try {
                        return processTable(t, db, allFks, fkTargets, probing, domain, piiColumns);
                    } finally {
                        permits.release();
                    }
                }));
            }
            // _OrderedCommitQueue: commit strictly in canonical table order
            for (Future<TableWork> f : futures) {
                TableWork w;
                try {
                    w = f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationToken.CancelledException();
                } catch (java.util.concurrent.ExecutionException e) {
                    log.warn("Table processing failed: {}", e.getCause().getMessage());
                    continue;
                }
                if (w != null) {
                    commit(databaseName, w);
                    done++;
                }
            }
        }
        cancel.check();
        return done;
    }

    private TableWork processTable(Map<String, Object> table, SourceDatabase db, List<Map<String, Object>> allFks,
            Set<String> fkTargets, boolean probing, String domain, Set<String> piiColumns) {
        String tableId = (String) table.get("id");
        List<Map<String, Object>> columns = catalog.columnsOfTable(tableId);
        Map<String, ColumnProfile> profiles = profiler.profile(db, (String) table.get("schema_name"),
                (String) table.get("name"), columns, probing);
        for (Map<String, Object> c : columns) {
            ColumnProfile p = profiles.get(c.get("name"));
            if (p != null) {
                catalog.updateProfile((String) c.get("id"), Json.write(p.sampleValues()), p.isUnique(), p.format());
                c.put("sample_values", Json.write(p.sampleValues()));
                c.put("format", p.format());
            }
        }
        List<Map<String, Object>> declared = new ArrayList<>();
        for (Map<String, Object> f : allFks) {
            if (tableId.equals(f.get("source_table_id"))) {
                declared.add(f);
            }
        }
        Set<String> targetCols = columns.stream().map(c -> (String) c.get("name"))
                .filter(n -> fkTargets.contains(tableId + "." + n)).collect(Collectors.toSet());
        // NFR-SEC-04: a table with PII columns sends no sample values to the LLM unless allowed
        boolean samplesToLlm = props.security().piiSamplesToLlm()
                || columns.stream().noneMatch(c -> piiColumns.contains((String) c.get("id")));
        FkResult fk = taxonomy.suggestForeignKeys(table, columns, declared, profiles, targetCols, samplesToLlm);
        Set<String> fkColumns = declared.stream().map(f -> (String) f.get("source_column")).collect(Collectors.toSet());
        fk.suggestions().stream().map(PotentialFkSuggestion::column_name).forEach(fkColumns::add);
        // is_junction_table only when the LLM verdict and structural evidence (≥ 2 FK roles) agree
        boolean junction = fk.junction() && fkColumns.size() >= 2;
        List<Spec> specs = taxonomy.attributeSpecs(columns, fkColumns, profiles, samplesToLlm);
        List<TermProposal> terms = taxonomy.extractTerms(table, specs, domain);
        return new TableWork(table, terms, specs, junction);
    }

    private void commit(String databaseName, TableWork w) {
        Instant now = time.now();
        String tableId = (String) w.table().get("id");
        if (w.junction()) {
            catalog.markJunction(tableId);
        }
        Map<String, Spec> specs = new HashMap<>();
        w.specs().forEach(s -> specs.put(s.sourceColumn(), s));
        for (TermProposal t : w.terms()) {
            String termId = semantic.mergeTerm(t.name(), t.description(), t.synonyms(), "semantic", now);
            semantic.linkTableTerm(tableId, termId);
            t.attributes().forEach((col, label) -> {
                Spec s = specs.get(col);
                if (s != null) {
                    semantic.mergeColumnAttribute(label, s.description(), s.datatype(), col, t.name(), termId, tableId,
                            s.columnId(), now);
                }
            });
            Map<String, Object> term = semantic.termById(termId).orElseThrow();
            List<Map<String, Object>> attrs = semantic.attributesOfDatabase((String) w.table().get("database_id"))
                    .stream().filter(a -> termId.equals(a.get("term_id")) && tableId.equals(a.get("table_id"))).toList();
            try {
                semanticEmbedder.write(databaseName, semanticEmbedder.termRows(databaseName, term, attrs, true));
            } catch (RuntimeException e) {
                log.warn("Embedding of Term {} failed: {}", t.name(), e.getMessage());
            }
        }
    }

    // ================================================================== stage 2: semantic FKs

    private int stageSemanticFks(String databaseId, String databaseName, SourceDatabase db) {
        Map<String, Map<String, Object>> attrOfColumn = new HashMap<>();
        for (Map<String, Object> a : semantic.attributesOfDatabase(databaseId)) {
            attrOfColumn.put((String) a.get("column_id"), a);
        }
        Map<String, String> declaredTarget = new HashMap<>();
        for (Map<String, Object> f : catalog.foreignKeys(databaseId)) {
            declaredTarget.put((String) f.get("source_column_id"), (String) f.get("target_column_id"));
        }
        int created = 0;
        Set<String> piiColumns = props.security().piiSamplesToLlm() ? Set.of() : pii.piiColumnIds();
        for (Map<String, Object> c : catalog.columnsOfDatabase(databaseId)) {
            String colId = (String) c.get("id");
            if (semantic.columnHasSemanticEdge(colId)) {
                continue;
            }
            String target = declaredTarget.get(colId);
            if (target != null) {
                // fast path: declared FK → attribute of the target column; self references are dropped
                Map<String, Object> attr = attrOfColumn.get(target);
                if (attr != null && !c.get("table_id").equals(attr.get("table_id"))) {
                    semantic.addSemanticFk(colId, (String) attr.get("id"));
                    created++;
                }
                continue;
            }
            String attrId = resolveViaVdb(databaseName, db, c, !piiColumns.contains(colId));
            if (attrId != null) {
                semantic.addSemanticFk(colId, attrId);
                created++;
            }
        }
        created -= pruneDisjoint(databaseId, db);
        selectJoinDefaults(databaseId);
        return created;
    }

    private String resolveViaVdb(String databaseName, SourceDatabase db, Map<String, Object> col, boolean samplesToLlm) {
        String text = EmbeddingTexts.columnText(databaseName, (String) col.get("schema_name"), (String) col.get("table_name"),
                (String) col.get("name"), (String) col.get("data_type"), (String) col.get("description"),
                Json.readList((String) col.get("sample_values")));
        List<SearchHit> hits;
        try {
            hits = vectors.search(VectorStore.SEMANTIC_LAYER, embedder.embedQuery(text), VDB_TOP_K * 3,
                    VectorFilter.of(databaseName, "ColumnAttribute"), null).stream()
                    .filter(h -> !col.get("table_id").equals(h.meta("table_id")))
                    // only columns that can serve as a referenced key
                    .filter(h -> !Boolean.FALSE.equals(h.meta("is_unique")))
                    .limit(VDB_TOP_K).toList();
        } catch (RuntimeException e) {
            log.warn("Semantic FK search for {} failed: {}", col.get("name"), e.getMessage());
            return null;
        }
        if (hits.isEmpty()) {
            return null;
        }
        String ctx = "Foreign-key column: " + col.get("name") + " (table: " + col.get("table_name") + ")\n"
                + "Description: " + (col.get("description") == null ? "(none)" : col.get("description")) + "\n"
                + "Sample values: " + (samplesToLlm ? samplesText(col.get("sample_values")) : "(withheld)");
        String candidates = hits.stream().map(h -> "column_id=" + h.id() + " | " + truncate(h.record().text(), 300))
                .collect(Collectors.joining("\n"));
        String human = ctx + "\n\nCandidate PK columns (from vector search):\n" + candidates + "\n\n"
                + "Which candidate does this FK column reference? "
                + "Return its exact column_id value, or null if none are a confident match.";
        FkHitSelection sel = llm.tryStructured(ModelRole.REASONING, List.of(
                LlmMessage.system(Prompts.get("semantic_fk__system_prompt")), LlmMessage.user(human)),
                FkHitSelection.class).orElse(null);
        if (sel != null && sel.selected_id() != null) {
            String id = sel.selected_id().strip();
            if (hits.stream().anyMatch(h -> h.id().equals(id))) {
                return id;
            }
        }
        // the LLM abstained: check live which candidate shares values
        List<String> samples = Json.readList((String) col.get("sample_values")).stream().map(String::valueOf).toList();
        if (samples.isEmpty()) {
            return null;
        }
        for (SearchHit h : hits) {
            if (Boolean.TRUE.equals(overlaps(db, (String) h.meta("schema_name"), (String) h.meta("table_name"),
                    (String) h.meta("source_column"), samples))) {
                return h.id();
            }
        }
        return null;
    }

    private static String samplesText(Object raw) {
        List<Object> v = raw instanceof String s ? Json.readList(s) : List.of();
        return v.isEmpty() ? "(none)" : v.stream().map(String::valueOf).filter(x -> x.length() <= 30)
                .collect(Collectors.joining(", "));
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) : s;
    }

    /**
     * Whether any of the sample values exists in the candidate column (bounded, read-only).
     *
     * @return {@code null} when the probe itself failed (timeout, missing rights, a type that
     *     cannot be cast): "unknown" is not "disjoint"
     */
    private Boolean overlaps(SourceDatabase db, String schema, String table, String column, List<String> samples) {
        if (table == null || column == null || samples.isEmpty()) {
            return false;
        }
        String cast = "CAST(" + db.quote(column) + " AS " + ("tsql".equals(db.dialect()) ? "NVARCHAR(4000)"
                : "oracle".equals(db.dialect()) ? "VARCHAR2(4000)" : "VARCHAR(4000)") + ")";
        String placeholders = samples.stream().map(s -> "?").collect(Collectors.joining(", "));
        String sql = db.limit("SELECT DISTINCT " + cast + " AS matched_value FROM " + db.qualify(schema, table)
                + " WHERE " + cast + " IN (" + placeholders + ")", samples.size());
        try {
            return !db.execute(sql, new ArrayList<>(samples), 30, samples.size()).rows().isEmpty();
        } catch (CancellationToken.CancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            log.debug("Value probe on {}.{}.{} failed: {}", schema, table, column, e.getMessage());
            return null;
        }
    }

    /** prune_disjoint_semantic_fks: delete edges whose sampled values never occur in the target. */
    private int pruneDisjoint(String databaseId, SourceDatabase db) {
        int removed = 0;
        Map<String, Map<String, Object>> cols = new HashMap<>();
        catalog.columnsOfDatabase(databaseId).forEach(c -> cols.put((String) c.get("id"), c));
        for (Map<String, Object> e : semantic.semanticFkEdges(databaseId)) {
            Map<String, Object> src = cols.get(e.get("source_column_id"));
            if (src == null) {
                continue;
            }
            List<String> samples = Json.readList((String) src.get("sample_values")).stream().map(String::valueOf).toList();
            if (samples.isEmpty()) {
                continue;
            }
            // only a probe that ran and found nothing proves the edge wrong; a failed probe keeps it
            if (Boolean.FALSE.equals(overlaps(db, (String) e.get("target_schema"), (String) e.get("target_table"),
                    (String) e.get("target_column"), samples))) {
                semantic.deleteSemanticFk((String) e.get("source_column_id"), (String) e.get("attribute_id"));
                removed++;
            }
        }
        return removed;
    }

    /** select_semantic_fk_join_defaults: exactly one use_in_join_paths = true per (source table, attribute). */
    private void selectJoinDefaults(String databaseId) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> e : semantic.semanticFkEdges(databaseId)) {
            groups.computeIfAbsent(e.get("source_table_id") + "|" + e.get("attribute_id"), k -> new ArrayList<>()).add(e);
        }
        for (List<Map<String, Object>> group : groups.values()) {
            if (group.size() < 2) {
                semantic.setJoinDefault((String) group.get(0).get("source_column_id"),
                        (String) group.get(0).get("attribute_id"), true);
                continue;
            }
            Map<String, Object> first = group.get(0);
            String candidates = group.stream().map(r -> "- source_column_id: " + r.get("source_column_id") + "\n  column: "
                    + r.get("source_column") + "\n  description: (none)").collect(Collectors.joining("\n"));
            String prompt = "Source table: " + first.get("source_table") + "\n"
                    + "Source table description: (none)\n"
                    + "Target table: " + first.get("target_table") + "\n"
                    + "Target table description: (none)\n"
                    + "Target column: " + first.get("target_column") + "\n"
                    + "Target column description: (none)\n"
                    + "Target semantic attribute: (none)\n\n"
                    + "Candidate relationships:\n" + candidates + "\n";
            String chosen = llm.tryStructured(ModelRole.REASONING, List.of(
                    LlmMessage.system(Prompts.get("semantic_fk__join_default_system_prompt")), LlmMessage.user(prompt)),
                    SemanticFkJoinDefaultSelection.class).map(SemanticFkJoinDefaultSelection::selected_column_id)
                    .orElse(null);
            String pick = group.stream().map(r -> (String) r.get("source_column_id")).filter(id -> id.equals(chosen))
                    .findFirst().orElse((String) first.get("source_column_id"));
            for (Map<String, Object> r : group) {
                semantic.setJoinDefault((String) r.get("source_column_id"), (String) r.get("attribute_id"),
                        pick.equals(r.get("source_column_id")));
            }
        }
    }

    // ================================================================== stage 3: SQL attributes

    private int stageSqlAttributes(String databaseId, String databaseName) {
        int created = 0;
        Set<String> seenTerms = new HashSet<>();
        for (Map<String, Object> t : catalog.tablesOfDatabase(databaseId)) {
            for (Map<String, Object> term : semantic.termsOfTable((String) t.get("id"))) {
                String termId = (String) term.get("id");
                if (!seenTerms.add(termId)) {
                    continue;
                }
                List<String> tableIds = semantic.tablesOfTerm(termId).stream().map(x -> (String) x.get("id")).toList();
                List<Map<String, Object>> queries = catalog.sqlForTables(tableIds, 200);
                if (queries.isEmpty()) {
                    continue;
                }
                created += suggestFor(databaseName, term, queries);
            }
        }
        return created;
    }

    private int suggestFor(String databaseName, Map<String, Object> term, List<Map<String, Object>> queries) {
        boolean byUsage = props.semantic().rankSqlByUsage();
        Map<String, Double> score = new LinkedHashMap<>();
        Map<String, String> sqlOf = new HashMap<>();
        for (Map<String, Object> q : queries) {
            double uses = byUsage ? ((Number) q.getOrDefault("total_counter", 0)).doubleValue() : 0.0;
            for (String expr : expressions((String) q.get("sql_full_query"))) {
                score.merge(expr, uses, Double::sum);
                sqlOf.putIfAbsent(expr, (String) q.get("id"));
            }
        }
        if (score.isEmpty()) {
            return 0;
        }
        // with _usage_score always 0 (the original) every expression ties and insertion order wins
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(score.entrySet());
        if (byUsage) {
            ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        }
        ranked = ranked.subList(0, Math.min(SQL_EXPRESSIONS_TOP, ranked.size()));
        StringBuilder block = new StringBuilder();
        for (int i = 0; i < ranked.size(); i++) {
            block.append(i + 1).append(". [").append(String.format(Locale.ROOT, "%.0f", ranked.get(i).getValue()))
                    .append(" uses] ").append(ranked.get(i).getKey());
            if (i < ranked.size() - 1) {
                block.append('\n');
            }
        }
        String user = Prompts.render("sql_attribute_suggester__user", Map.of(
                "term_name", term.get("name"),
                "term_description_or_none", term.get("description") == null || String.valueOf(term.get("description"))
                        .isBlank() ? "(none)" : term.get("description"),
                "expr_block", block.toString()));
        if (byUsage) {
            // FR-SEM-04: the ranking now really is by execution count
            user = user.replace("(ranked by 3-month execution count)", "(ranked by execution count)");
        }
        Suggestions s = llm.tryStructured(ModelRole.REASONING, List.of(
                LlmMessage.system(Prompts.get("sql_attribute_suggester__system")), LlmMessage.user(user)),
                Suggestions.class).orElse(null);
        if (s == null) {
            return 0;
        }
        int created = 0;
        Instant now = time.now();
        for (SuggestedAttr a : s.suggestions() == null ? List.<SuggestedAttr>of() : s.suggestions()) {
            // sql_attribute.expression is NOT NULL: an incomplete suggestion is skipped, not a failed pass
            if (a == null || a.name() == null || a.name().isBlank() || a.expression() == null || a.expression().isBlank()
                    || semantic.sqlAttributeByName(a.name().strip()).isPresent()) {
                continue;
            }
            String id = semantic.insertSqlAttribute(a.name().strip(), a.description(), a.expression(), "sql",
                    (String) term.get("id"), null, null, now);
            String sqlId = sqlOf.get(a.expression());
            if (sqlId != null) {
                semantic.linkSqlAttributeSql(id, sqlId);
            }
            embedSqlAttribute(databaseName, id);
            created++;
        }
        return created;
    }

    /** Filters, aggregations and functions of a statement (candidate expressions). */
    static List<String> expressions(String sql) {
        List<String> out = new ArrayList<>();
        try {
            if (!(CCJSqlParserUtil.parse(sql) instanceof Select select) || !(select instanceof PlainSelect ps)) {
                return out;
            }
            for (SelectItem<?> si : ps.getSelectItems()) {
                Expression e = si.getExpression();
                if (e instanceof Function || e instanceof CaseExpression) {
                    out.add(e.toString());
                }
            }
            if (ps.getWhere() != null) {
                conjuncts(ps.getWhere(), out);
            }
        } catch (Exception e) {
            // unparsable statements contribute nothing
        }
        return out;
    }

    private static void conjuncts(Expression e, List<String> out) {
        if (e instanceof AndExpression and) {
            conjuncts(and.getLeftExpression(), out);
            conjuncts(and.getRightExpression(), out);
        } else if (e instanceof ComparisonOperator || e instanceof InExpression || e instanceof LikeExpression
                || e instanceof IsNullExpression) {
            out.add(e.toString());
        }
    }

    public void embedSqlAttribute(String databaseName, String id) {
        semantic.sqlAttributeById(id).ifPresent(a -> {
            try {
                semanticEmbedder.write(databaseName, List.of(SemanticEmbedder.sqlAttributeRow(databaseName, a)));
            } catch (RuntimeException e) {
                log.warn("Embedding of SqlAttribute {} failed: {}", a.get("name"), e.getMessage());
            }
        });
    }

    // ================================================================== stage 4: bridge tables

    private int stageBridges(String databaseId, String databaseName) {
        List<Map<String, Object>> fks = catalog.foreignKeys(databaseId);
        Map<String, List<Map<String, Object>>> bySource = new LinkedHashMap<>();
        fks.forEach(f -> bySource.computeIfAbsent((String) f.get("source_table_id"), k -> new ArrayList<>()).add(f));
        int created = 0;
        for (Map<String, Object> t : catalog.tablesOfDatabase(databaseId)) {
            String tableId = (String) t.get("id");
            List<Map<String, Object>> tfks = bySource.getOrDefault(tableId, List.of());
            List<Map<String, Object>> cols = catalog.columnsOfTable(tableId);
            Set<String> fkCols = tfks.stream().map(f -> (String) f.get("source_column")).collect(Collectors.toSet());
            boolean pureFk = tfks.size() >= 2 && cols.stream().allMatch(c -> fkCols.contains(c.get("name")));
            if (!pureFk && !(Boolean.TRUE.equals(t.get("is_junction_table")) && tfks.size() >= 2)) {
                continue;
            }
            if (!pureFk) {
                // payload junctions keep their own Term; only pure FK tables are bridges here
                continue;
            }
            created += bridge(databaseName, t, tfks);
        }
        return created;
    }

    private int bridge(String databaseName, Map<String, Object> t, List<Map<String, Object>> tfks) {
        String qualified = t.get("schema_name") + "." + t.get("name");
        List<String> related = tfks.stream().map(f -> f.get("target_schema") + "." + f.get("target_table")).distinct()
                .sorted().toList();
        boolean self = related.size() == 1;
        String joinHint = self
                ? "This is a self-referential bridge: both FK columns point at the same entity table. Alias that table"
                        + " twice in the JOIN (e.g. product AS buyer and product AS also_bought) so each FK role is clear."
                : "Join the bridge table to each related entity table using the FK pairs.";
        String fkBlock = tfks.stream().map(f -> "  - " + qualified + "." + f.get("source_column") + " -> "
                + f.get("target_schema") + "." + f.get("target_table") + "." + f.get("target_column"))
                .collect(Collectors.joining("\n"));
        String user = Prompts.render("bridge_tables__user", Map.of("qualified_bridge", qualified,
                "join_related_tables_or_unknown", related.isEmpty() ? "(unknown)" : String.join(", ", related),
                "format_fk_block_fk_pairs", fkBlock, "join_hint", joinHint));
        BridgeSqlAttributeProposal p = llm.tryStructured(ModelRole.REASONING, List.of(
                LlmMessage.system(Prompts.get("bridge_tables__system")), LlmMessage.user(user)),
                BridgeSqlAttributeProposal.class).orElse(null);
        if (p == null || p.name() == null || p.name().isBlank() || p.expression() == null || p.expression().isBlank()
                || semantic.sqlAttributeByName(p.name().strip()).isPresent()) {
            return 0;
        }
        String termId = targetTerm(p, tfks);
        if (termId == null) {
            return 0;
        }
        String id = semantic.insertSqlAttribute(p.name().strip(), p.description(), p.expression(), "bridgeTable", termId,
                null, null, time.now());
        embedSqlAttribute(databaseName, id);
        return 1;
    }

    /** The better fitting target Term, chosen by the rerank model when configured (FR-SEM-05). */
    private String targetTerm(BridgeSqlAttributeProposal p, List<Map<String, Object>> tfks) {
        List<Map<String, Object>> terms = new ArrayList<>();
        for (Map<String, Object> f : tfks) {
            terms.addAll(semantic.termsOfTable((String) f.get("target_table_id")));
        }
        if (terms.isEmpty()) {
            return null;
        }
        if (rerank.configured() && terms.size() > 1) {
            try {
                List<Double> scores = rerank.scores(p.name() + ": " + p.description(),
                        terms.stream().map(t -> t.get("name") + ". " + TableTaxonomy.nz(t.get("description"))).toList());
                int best = 0;
                for (int i = 1; i < scores.size(); i++) {
                    if (scores.get(i) > scores.get(best)) {
                        best = i;
                    }
                }
                return (String) terms.get(best).get("id");
            } catch (RuntimeException e) {
                log.warn("Rerank of bridge target failed: {}", e.getMessage());
            }
        }
        return (String) terms.get(0).get("id");
    }
}
