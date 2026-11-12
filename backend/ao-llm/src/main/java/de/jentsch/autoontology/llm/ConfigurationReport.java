// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import de.jentsch.autoontology.common.AoProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

/**
 * Logs the effective configuration once at startup, after all beans exist and before the
 * schedulers start. API keys are shown with their first 10 characters only (at most half of a
 * short key), passwords in JDBC URLs are masked. Off with {@code ao.log-configuration=false}.
 */
public class ConfigurationReport implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationReport.class);
    private static final int KEY_PREFIX = 10;
    private static final Pattern URL_PASSWORD =
            Pattern.compile("(?i)((?:password|pwd)\\s*=\\s*)[^;&,\\s]*");
    private static final Pattern URL_USERINFO = Pattern.compile("(//[^/:@\\s]+:)[^@/\\s]+@");

    private final AoProperties props;
    private final Environment env;
    private final ObjectProvider<LlmGateway> gateway;
    private final ObjectProvider<Embedder> embedder;

    public ConfigurationReport(
            AoProperties props, Environment env, ObjectProvider<LlmGateway> gateway, ObjectProvider<Embedder> embedder) {
        this.props = props;
        this.env = env;
        this.gateway = gateway;
        this.embedder = embedder;
    }

    @Override
    public void afterSingletonsInstantiated() {
        log.info("Auto-Ontology configuration:{}", render());
    }

    /** The report text (one setting per line). */
    public String render() {
        List<String> l = new ArrayList<>();
        l.add("profiles          = " + Arrays.toString(env.getActiveProfiles()));
        l.add("server            = " + env.getProperty("server.address", "*") + ":" + env.getProperty("server.port", "8080"));
        l.add("api-prefix        = " + props.apiPrefix() + "  (nl-chart " + props.resolvedNlChartPrefix() + ")");
        l.add("user              = " + props.user().id() + " (" + props.user().displayName() + ")");
        l.add("clock.fixed-now   = " + orDash(props.clock().fixedNow()));
        l.add("store datasource  = " + maskUrl(env.getProperty("spring.datasource.url", "-")));
        l.add("-- models");
        for (ModelRole role : ModelRole.values()) {
            ModelSettings s = ModelSettings.resolve(props.models(), role);
            l.add(String.format("%-17s = endpoint=%s, model=%s, api-key=%s, structured-output=%s",
                    role.name().toLowerCase(java.util.Locale.ROOT), orDash(s.endpoint()), orDash(s.model()), maskKey(s.apiKey()),
                    s.structuredOutput()));
        }
        l.add("llm               = timeout=" + props.llm().invokeTimeoutS() + "s, max-inflight="
                + props.llm().maxInflight() + ", max-retries=" + props.llm().maxRetries()
                + ", gateway=" + beanClass(gateway));
        l.add("embedding         = provider=" + props.embedding().provider() + ", batch-size="
                + props.embedding().batchSize() + ", send-input-type=" + props.embedding().sendInputType()
                + ", embedder=" + beanClass(embedder));
        l.add("-- vector store");
        AoProperties.Vector v = props.vector();
        l.add("vector            = store=" + v.store() + ", dimensions=" + v.dimensions() + ", persist=" + v.persist());
        if ("qdrant".equalsIgnoreCase(v.store())) {
            AoProperties.Qdrant q = v.qdrant();
            l.add("qdrant            = " + q.host() + ":" + q.port() + ", tls=" + q.useTls() + ", api-key="
                    + maskKey(q.apiKey()) + ", collection-prefix=" + orDash(q.collectionPrefix()));
        }
        l.add("-- ingest / semantic / agent");
        AoProperties.Ingest in = props.ingest();
        l.add("ingest            = interval=" + in.intervalHours() + "h, scheduler=" + in.schedulerEnabled());
        if (in.connectionStrings() != null && !in.connectionStrings().isBlank()) {
            for (String cs : in.connectionStrings().split("[,\\n]")) {
                if (!cs.isBlank()) {
                    l.add("  connection-string " + maskUrl(cs.trim()));
                }
            }
        }
        if (in.connections() != null) {
            for (AoProperties.ConnectionConfig c : in.connections()) {
                l.add("  connection " + c.name() + " = "
                        + (c.datasourceBean() != null ? "bean " + c.datasourceBean() : maskUrl(c.url()))
                        + ", schemas=" + (c.schemas() == null ? "-" : c.schemas())
                        + ", allow=" + orDash(c.tableAllowRegex()) + ", deny=" + orDash(c.tableDenyRegex()));
            }
        }
        l.add("semantic          = interval=" + props.semantic().intervalHours() + "h, scheduler="
                + props.semantic().schedulerEnabled() + ", llm-max-inflight=" + props.semantic().llmMaxInflight());
        AoProperties.Agent a = props.agent();
        l.add("agent             = recursion-limit=" + a.recursionLimit() + ", max-reconstructions="
                + a.maxReconstructionAttempts() + ", sql-gen-max-entities=" + a.sqlGenMaxEntities()
                + ", max-result-rows=" + a.maxResultRows() + ", validate-sql-values=" + a.validateSqlValues()
                + ", db-probe-proactive=" + a.dbProbeProactive());
        l.add("security          = pii-samples-to-llm=" + props.security().piiSamplesToLlm());
        l.add("nl-chart          = dataset-allowlist=" + (props.nlChart().datasetAllowlist() == null ? "-"
                : props.nlChart().datasetAllowlist()) + ", expose-metric-sql=" + props.nlChart().exposeMetricSql());
        l.add("mcp               = api-base-url=" + orDash(props.mcp().apiBaseUrl()));
        return "\n  " + String.join("\n  ", l);
    }

    /** First 10 characters of a key, at most half of it, plus its length. */
    static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "-";
        }
        int shown = Math.min(KEY_PREFIX, key.length() / 2);
        return key.substring(0, shown) + "… (" + key.length() + " chars)";
    }

    static String maskUrl(String url) {
        if (url == null) {
            return "-";
        }
        String masked = URL_PASSWORD.matcher(url).replaceAll("$1***");
        return URL_USERINFO.matcher(masked).replaceAll("$1***@");
    }

    private static String beanClass(ObjectProvider<?> p) {
        Object bean = p.getIfAvailable();
        return bean == null ? "-" : bean.getClass().getSimpleName();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
