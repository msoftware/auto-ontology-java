// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.store.GovernanceRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rules = saved search plus tags (FR-GOV-07, server/rules/service.py, ingestion_service/rules.py).
 * Applying tags exactly the hits the search showed (including the cap); labels carry
 * {@code rule_id}; replay after ingest and compilation.
 */
public class RuleService {
    private static final Logger log = LoggerFactory.getLogger(RuleService.class);

    private final GovernanceRepository governance;
    private final SearchService search;
    private final CurrentUser user;
    private final AoTime time;

    public RuleService(GovernanceRepository governance, SearchService search, CurrentUser user, AoTime time) {
        this.governance = governance;
        this.search = search;
        this.user = user;
        this.time = time;
    }

    /** Applies one rule; returns the number of new labels. */
    @SuppressWarnings("unchecked")
    public int apply(Map<String, Object> rule) {
        Object filters = rule.get("filters");
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("search_term", rule.get("search_term"));
        body.put("text_match_option", rule.get("text_match_option"));
        body.put("filters", filters instanceof Map<?, ?> ? filters : Map.of());
        List<Map<String, Object>> hits = search.search(SearchService.request(body));
        List<Map<String, Object>> tags = (List<Map<String, Object>>) rule.get("tags");
        int added = 0;
        for (Map<String, Object> hit : hits) {
            String target = SearchService.tagTarget((String) hit.get("type"));
            if (target == null) {
                continue;
            }
            for (Map<String, Object> tag : tags) {
                if (governance.addTagTarget((String) tag.get("id"), target, (String) hit.get("id"),
                        String.valueOf(rule.getOrDefault("created_by", user.id())), (String) rule.get("id"), time.now())) {
                    added++;
                }
            }
        }
        return added;
    }

    /** Re-applies every rule (after ingest/compilation); errors are logged, never fatal (NFR-REL-01). */
    public void replayAll() {
        for (Map<String, Object> rule : governance.rules()) {
            try {
                apply(rule);
            } catch (RuntimeException e) {
                log.warn("Replay of rule {} failed: {}", rule.get("name"), e.getMessage());
            }
        }
    }
}
