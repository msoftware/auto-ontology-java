// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.store;

import de.jentsch.autoontology.common.AoTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instance-wide runtime switches stored in {@code app_configurations} (FR-CFG-02). Defaults when no
 * entry exists follow infra/feature_flags.py and frontend/lib/configurations.ts; invalid values
 * fall back to the default and are logged (FR-SEM-07).
 */
public class RuntimeSettings {
    public static final String VISUALIZATION = "visualization_enabled";
    public static final String SQL_TIMEOUT = "sql_query_timeout_seconds";
    public static final String DISTINCT_PROBING = "distinct_value_probing_enabled";
    public static final String PII_DETECTION = "pii_detection_enabled";
    public static final String SEMANTIC_COMPILATION = "semantic_compilation_enabled";

    private static final Logger log = LoggerFactory.getLogger(RuntimeSettings.class);

    private final AppRepository app;
    private final AoTime time;

    public RuntimeSettings(AppRepository app, AoTime time) {
        this.app = app;
        this.time = time;
    }

    public boolean visualizationEnabled() {
        return flag(VISUALIZATION, true);
    }

    public boolean distinctValueProbingEnabled() {
        return flag(DISTINCT_PROBING, true);
    }

    public boolean piiDetectionEnabled() {
        return flag(PII_DETECTION, false);
    }

    public boolean semanticCompilationEnabled() {
        return flag(SEMANTIC_COMPILATION, false);
    }

    /** 1..3600 seconds, default 30. */
    public int sqlQueryTimeoutSeconds() {
        String raw = app.config(SQL_TIMEOUT).orElse(null);
        if (raw == null) {
            return 30;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v >= 1 && v <= 3600) {
                return v;
            }
        } catch (NumberFormatException e) {
            // fall through to the default
        }
        log.warn("Invalid {} value {}; using default 30", SQL_TIMEOUT, raw);
        return 30;
    }

    public void set(String key, Object value) {
        app.setConfig(key, String.valueOf(value), time.now());
    }

    /** Seeds a value only when nothing is stored yet (demo profile, FR-DEMO-01). */
    public void seed(String key, Object value) {
        if (app.config(key).isEmpty()) {
            set(key, value);
        }
    }

    private boolean flag(String key, boolean def) {
        String raw = app.config(key).orElse(null);
        if (raw == null) {
            return def;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("true") || v.equals("1")) {
            return true;
        }
        if (v.equals("false") || v.equals("0")) {
            return false;
        }
        log.warn("Invalid {} value '{}'; using default {}", key, raw, def);
        return def;
    }
}
