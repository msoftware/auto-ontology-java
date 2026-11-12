// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.agent.AgentModels.QuestionExtractionLiteModel;
import de.jentsch.autoontology.agent.Retrieval.Candidate;
import de.jentsch.autoontology.llm.LlmMessage;
import de.jentsch.autoontology.llm.ModelRole;
import de.jentsch.autoontology.llm.Prompts;
import de.jentsch.autoontology.store.AppRepository;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.vector.VectorStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Answerability (retrieval/entity_coverage, FR-AGT-09): question_extraction → retrieve_candidates →
 * coverage_grade. Hits are filtered by a distance threshold (default 0.75); coverage is the share
 * of entities with a hit. Runs are serialized (server/metadata/service.py).
 */
public class EntityCoverageService {
    public static final double DEFAULT_MAX_DISTANCE = 0.75;

    private final AgentServices s;
    private final AppRepository app;
    private final SemanticRepository semantic;
    private final ReentrantLock lock = new ReentrantLock();

    public EntityCoverageService(AgentServices services, AppRepository app, SemanticRepository semantic) {
        this.s = services;
        this.app = app;
        this.semantic = semantic;
    }

    public Map<String, Object> coverage(String question, String targetDb, Double maxDistance,
            boolean returnUncovered) {
        lock.lock();
        try {
            double threshold = maxDistance == null ? DEFAULT_MAX_DISTANCE : maxDistance;
            // domain rules: custom analyses plus acronyms (entity_coverage/main.py)
            java.util.function.Function<Map<String, Object>, String> line = a -> "- " + a.get("name")
                    + (a.get("description") == null ? "" : ": " + a.get("description"));
            String domainRules = semantic.customAnalyses().stream().map(line).collect(Collectors.joining("\n")) + "\n"
                    + app.acronyms().stream().map(line).collect(Collectors.joining("\n"));
            String prompt = Prompts.render("entity_extraction", Map.of("question", question,
                    "max_entities", s.props().agent().sqlGenMaxEntities()))
                    + (domainRules.isBlank() ? "" : "\nDomain rules and glossary:\n" + domainRules.strip() + "\n");
            List<String> entities = s.llm().tryStructured(ModelRole.NON_REASONING, List.of(LlmMessage.user(prompt)),
                    QuestionExtractionLiteModel.class).map(QuestionExtractionLiteModel::required_entity_name)
                    .filter(l -> !l.isEmpty()).orElse(List.of(question));
            List<Map<String, Object>> perEntity = new ArrayList<>();
            List<String> uncovered = new ArrayList<>();
            int covered = 0;
            for (String entity : entities) {
                List<Candidate> hits = Retrieval.search(s, VectorStore.SEMANTIC_LAYER, entity, entity, 5, targetDb,
                        Retrieval.SEMANTIC_LABELS, threshold);
                if (hits.isEmpty()) {
                    hits = Retrieval.search(s, VectorStore.DATA_LAYER, entity, entity, 5, targetDb, List.of("Table", "Column"),
                            threshold);
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("entity", entity);
                e.put("candidates", hits.stream().map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.id());
                    m.put("label", c.label());
                    m.put("name", c.name());
                    m.put("database_name", c.databaseName());
                    m.put("distance", c.distance());
                    m.put("text", c.text());
                    return m;
                }).toList());
                perEntity.add(e);
                if (hits.isEmpty()) {
                    uncovered.add(entity);
                } else {
                    covered++;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("coverage", entities.isEmpty() ? 0.0 : (double) covered / entities.size());
            out.put("candidates", perEntity);
            if (returnUncovered) {
                out.put("uncovered_entities", uncovered);
            }
            return out;
        } finally {
            lock.unlock();
        }
    }
}
