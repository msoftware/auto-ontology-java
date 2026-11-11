// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All {@code ao.*} settings (FR-CFG-01). The environment variables of the original
 * (CONNECTION_STRINGS, DEFAULT_MODELS_*, ...) are mapped onto these keys in
 * {@code ao-defaults.properties}.
 */
@ConfigurationProperties("ao")
public record AoProperties(
        @DefaultValue("/api") String apiPrefix,
        @DefaultValue User user,
        @DefaultValue Clock clock,
        @DefaultValue Vector vector,
        @DefaultValue Models models,
        @DefaultValue Embedding embedding,
        @DefaultValue Llm llm,
        @DefaultValue Agent agent,
        @DefaultValue Semantic semantic,
        @DefaultValue Ingest ingest,
        @DefaultValue Security security,
        @DefaultValue NlChart nlChart,
        @DefaultValue Mcp mcp,
        @DefaultValue Dashboard dashboard,
        @DefaultValue("false") boolean demo) {

    /** FR-AUTH-01: the fixed user every request runs as. */
    public record User(@DefaultValue("local") String id, @DefaultValue("Local user") String displayName) {}

    /** FR-DEMO-02: optional fixed "now" (ISO date or instant) for reproducible relative dates. */
    public record Clock(String fixedNow) {}

    /** FR-VEC-01..05. */
    public record Vector(
            @DefaultValue("qdrant") String store,
            @DefaultValue("2048") int dimensions,
            @DefaultValue("false") boolean persist,
            @DefaultValue Qdrant qdrant) {}

    /** Qdrant connection; the API key never appears in {@link #toString()} (NFR-SEC-02). */
    public record Qdrant(
            @DefaultValue("localhost") String host,
            @DefaultValue("6334") int port,
            @DefaultValue("false") boolean useTls,
            String apiKey,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("") String collectionPrefix,
            @DefaultValue("256") int upsertBatchSize,
            @DefaultValue("float32") String datatype) {
        @Override
        public String toString() {
            return "Qdrant[host=" + host + ", port=" + port + ", tls=" + useTls + "]";
        }
    }

    /** FR-LLM-01: four model roles, each falling back to {@code defaults}. */
    public record Models(
            @DefaultValue Model defaults,
            @DefaultValue Model reasoning,
            @DefaultValue Model nonReasoning,
            @DefaultValue Model embed,
            @DefaultValue Model rerank) {}

    /**
     * One model role. {@code structuredOutput} is {@code json_schema}, {@code json_object} or
     * {@code tool_calling} (FR-LLM-02, FR-LLM-06).
     */
    public record Model(String endpoint, String apiKey, String model, String structuredOutput) {
        @Override
        public String toString() {
            return "Model[endpoint=" + endpoint + ", model=" + model + "]";
        }
    }

    /**
     * FR-LLM-04. {@code provider} is {@code openai} (OpenAI-compatible /embeddings) or {@code hash}
     * (deterministic local embedding for tests and the offline demo, FR-DEMO-06).
     */
    public record Embedding(
            @DefaultValue("openai") String provider,
            @DefaultValue("true") boolean sendInputType,
            @DefaultValue("32") int batchSize,
            @DefaultValue("nemotron-3.5-embed-1b-nvfp4") List<String> textPrefixedModels) {}

    /** FR-LLM-03 limits. */
    public record Llm(
            @DefaultValue("120") int invokeTimeoutS,
            @DefaultValue("6") int maxInflight,
            @DefaultValue("3") int maxRetries) {}

    /** Agent switches (FR-AGT-01, FR-CFG-01 "Agent"). */
    public record Agent(
            @DefaultValue("false") boolean validateSqlValues,
            @DefaultValue("5") int sqlGenMaxEntities,
            @DefaultValue("7") int maxReconstructionAttempts,
            @DefaultValue("45") int recursionLimit,
            @DefaultValue("false") boolean dbProbeProactive,
            @DefaultValue("false") boolean dbProbeJoinPathCheck,
            @DefaultValue("false") boolean dbProbeJsonbPathCheck,
            @DefaultValue("false") boolean detectVacuousGroupBy,
            @DefaultValue("false") boolean intentValidationJoinsValidatedElsewhere,
            @DefaultValue("false") boolean preferCertified,
            @DefaultValue("20") int probeBudget,
            @DefaultValue("1000") int maxResultRows) {}

    /** FR-SEM-01, FR-SEM-04. */
    public record Semantic(
            @DefaultValue("true") boolean rankSqlByUsage,
            @DefaultValue("6") int llmMaxInflight,
            @DefaultValue("24") int intervalHours,
            @DefaultValue("true") boolean schedulerEnabled) {}

    /** FR-ING-02, FR-CON-04. */
    public record Ingest(
            @DefaultValue("24") int intervalHours,
            @DefaultValue("") String connectionStrings,
            @DefaultValue("true") boolean schedulerEnabled,
            List<ConnectionConfig> connections) {}

    /** FR-NLC-01: a configured connection, optionally backed by a DataSource bean. */
    public record ConnectionConfig(
            String name,
            String url,
            String datasourceBean,
            List<String> schemas,
            String tableAllowRegex,
            String tableDenyRegex) {}

    /** NFR-SEC-04. */
    public record Security(@DefaultValue("false") boolean piiSamplesToLlm) {}

    /** Section 23.6. */
    public record NlChart(
            String apiPrefix, List<String> datasetAllowlist, @DefaultValue("false") boolean exposeMetricSql) {}

    /** FR-MCP-01. */
    public record Mcp(String apiBaseUrl) {}

    /** FR-NLC-01: {@code qd_database.database_name} to AO connection name. */
    public record Dashboard(Map<String, String> databaseMapping) {}

    /** Resolved NL-chart prefix: default {@code ${ao.api-prefix}/nl-chart}. */
    public String resolvedNlChartPrefix() {
        String p = nlChart.apiPrefix();
        return p == null || p.isBlank() ? apiPrefix + "/nl-chart" : p;
    }
}
