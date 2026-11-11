// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.GovernanceRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.store.StoreJdbc;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-GOV-09: rules first (normalized names and types, high-confidence patterns, ported from
 * pii_detection/rules.py). The result is the shared tag {@code PII} on the column and
 * {@code pii_processed = true}; a manually removed tag is never set again because processed columns
 * are skipped. Undecided columns stay untagged (the LLM fallback of the original is not ported).
 */
public class PiiDetection implements IngestService.Stage {
    private static final Logger log = LoggerFactory.getLogger(PiiDetection.class);
    public static final String TAG = "PII";

    private static final String FIELD_PREFIXES = "preferred|personal|primary|secondary|alternate|business|billing|"
            + "shipping|mailing|customer|employee|contact|patient|member|person|client|user|household|home|work|postal";
    private static final String IDENTIFIER_PREFIXES = "employee|customer|contact|patient|member|person|client|user|account";

    /** A rule matching the whole normalized or compact column name. */
    record Rule(Pattern words, Pattern compact, String category) {
        boolean matches(String normalized, String compactName) {
            return words.matcher(normalized).matches() || compact.matcher(compactName).matches();
        }
    }

    static Rule rule(String words, String category, String compact, String prefixes) {
        if (prefixes != null) {
            return new Rule(Pattern.compile("^(?:(?:" + prefixes + ") )*(?:" + words + ")$"),
                    Pattern.compile("^(?:" + prefixes + ")*(?:" + compact + ")$"), category);
        }
        return new Rule(Pattern.compile("^(?:" + words + ")$"), Pattern.compile("^(?:" + compact + ")$"), category);
    }

    static final List<Rule> DIRECT = List.of(
            rule("social security(?: number)?", "government_id", "ssn|socialsecurity(?:number)?", FIELD_PREFIXES),
            rule("passport(?: number)?|driver(?:s)? license", "government_id", "passport(?:number)?|drivers?license",
                    FIELD_PREFIXES),
            rule("taxpayer id|tax id|national id", "government_id", "taxpayerid|taxid|nationalid", FIELD_PREFIXES),
            rule("e mail|email address", "email_address", "emailaddress|email", FIELD_PREFIXES),
            rule("phone number|mobile phone|mobile number|cell phone|fax number", "phone_number",
                    "phonenumber|faxnumber|cellphone|mobilephone|telephone|mobile|phone|fax|cell", FIELD_PREFIXES),
            rule("date of birth|birth date|birthdate|dob", "date_of_birth", "dateofbirth|birthdate|dob", FIELD_PREFIXES),
            rule("credit card|debit card|card number", "payment_card", "creditcard|debitcard|cardnumber", FIELD_PREFIXES),
            rule("bank account(?: number)?|iban|swift code", "banking", "bankaccount(?:number)?|iban|swiftcode",
                    FIELD_PREFIXES),
            rule("passcode|pin code", "credential", "password|passcode|pincode", FIELD_PREFIXES),
            rule("fingerprint|biometric|retina scan|face print", "biometric",
                    "fingerprint|biometric|retinascan|faceprint", FIELD_PREFIXES),
            rule("ip address|mac address", "online_identifier", "ipaddress|macaddress", FIELD_PREFIXES));

    static final List<Rule> CONDITIONAL = List.of(
            rule("first name|last name|full name|middle name|maiden name|preferred name", "person_name",
                    "firstname|lastname|fullname|middlename|maidenname|preferredname", FIELD_PREFIXES),
            rule("name", "person_name", "name", null),
            rule("address|street|postal code|zip code", "postal_address",
                    "address(?:line\\d*)?|street|postalcode|zipcode", FIELD_PREFIXES),
            rule("age|gender|sex|marital status", "personal_attribute", "age|gender|sex|maritalstatus", null),
            rule("salary|income|credit score", "financial", "salary|income|creditscore", null),
            rule("id|account number", "identifier", "id|accountnumber", IDENTIFIER_PREFIXES));

    static final Pattern PERSON_TABLE = Pattern.compile("^(?:persons?|people|customers?|clients?|contacts?|users?|"
            + "employees?|patients?|applicants?|members?|students?|advisors?|representatives?|households?|"
            + "account holders?)$");
    static final Pattern NON_PERSON_TABLE = Pattern.compile("^(?:products?|assets?|strateg(?:y|ies)|platforms?|"
            + "transaction types?|business lines?|configurations?|audits?|metrics?|aggregates?|inventor(?:y|ies)|"
            + "catalogs?)$");
    static final Pattern NON_PII = Pattern.compile("^(?:product name|account type|account status|employee count|"
            + "bank name|firm id|buying group name|city name|customer category name|last password change|created at|"
            + "updated at)$");
    static final Pattern NON_PII_COMPACT = Pattern.compile("^(?:productname|accounttype|accountstatus|employeecount|"
            + "bankname|firmid|buyinggroupname|cityname|customercategoryname|lastpasswordchange|createdat|updatedat)$");

    /** Decision of the rules: PII category, "not_pii", or null (undecided). */
    public static String evaluate(String tableName, String columnName) {
        String col = normalize(columnName);
        String compact = col.replace(" ", "");
        String table = normalize(tableName);
        if (col.isEmpty() || NON_PII.matcher(col).matches() || NON_PII_COMPACT.matcher(compact).matches()) {
            return col.isEmpty() ? null : "not_pii";
        }
        for (Rule r : DIRECT) {
            if (r.matches(col, compact)) {
                return r.category();
            }
        }
        for (Rule r : CONDITIONAL) {
            if (!r.matches(col, compact)) {
                continue;
            }
            if (PERSON_TABLE.matcher(table).matches()) {
                return r.category();
            }
            if (NON_PERSON_TABLE.matcher(table).matches()) {
                return "not_pii";
            }
        }
        return null;
    }

    /** camelCase / snake_case / UPPER_CASE → lower-case words. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[^A-Za-z0-9]+", " ");
        return t.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private final CatalogRepository catalog;
    private final SemanticRepository semantic;
    private final GovernanceRepository governance;
    private final RuntimeSettings settings;
    private final CurrentUser user;
    private final AoTime time;
    private final StoreJdbc db;

    public PiiDetection(CatalogRepository catalog, SemanticRepository semantic, GovernanceRepository governance,
            RuntimeSettings settings, CurrentUser user, AoTime time, StoreJdbc db) {
        this.catalog = catalog;
        this.semantic = semantic;
        this.governance = governance;
        this.settings = settings;
        this.user = user;
        this.time = time;
        this.db = db;
    }

    private String tagId() {
        return governance.tagByName(TAG).map(t -> (String) t.get("id"))
                .orElseGet(() -> governance.addTag(TAG, user.id(), time.now()));
    }

    @Override
    public void afterCatalogWrite(String databaseId, String databaseName) {
        if (!settings.piiDetectionEnabled()) {
            return;
        }
        String tag = null;
        int tagged = 0;
        for (Map<String, Object> c : catalog.columnsOfDatabase(databaseId)) {
            if (Boolean.TRUE.equals(c.get("pii_processed"))) {
                continue;
            }
            String decision = evaluate((String) c.get("table_name"), (String) c.get("name"));
            if (decision != null && !"not_pii".equals(decision)) {
                if (tag == null) {
                    tag = tagId();
                }
                governance.addTagTarget(tag, "column", (String) c.get("id"), user.id(), null, time.now());
                tagged++;
            }
            catalog.setPiiProcessed((String) c.get("id"));
        }
        log.info("PII detection on {}: {} column(s) tagged", databaseName, tagged);
    }

    /** Propagation to ColumnAttributes and SqlAttributes that use PII columns (after compilation). */
    public void propagate() {
        if (!settings.piiDetectionEnabled()) {
            return;
        }
        var tag = governance.tagByName(TAG);
        if (tag.isEmpty()) {
            return;
        }
        String tagId = (String) tag.get().get("id");
        List<String> attrIds = db.column("""
                SELECT DISTINCT ha.attribute_id FROM column__has_attribute ha
                JOIN tag_target tt ON tt.column_id = ha.column_id AND tt.tag_id = :t
                JOIN column_attribute a ON a.id = ha.attribute_id WHERE a.pii_processed = FALSE
                """, Map.of("t", tagId), String.class);
        for (String a : attrIds) {
            governance.addTagTarget(tagId, "column_attribute", a, user.id(), null, time.now());
            semantic.patchAttribute(a, Map.of("pii_processed", true));
        }
        List<Map<String, Object>> piiColumns = db.list("""
                SELECT c.name AS column_name, t.name AS table_name FROM tag_target tt
                JOIN catalog_column c ON c.id = tt.column_id JOIN catalog_table t ON t.id = c.table_id
                WHERE tt.tag_id = :t
                """, Map.of("t", tagId));
        for (Map<String, Object> sa : semantic.sqlAttributes()) {
            if (Boolean.TRUE.equals(sa.get("pii_processed"))) {
                continue;
            }
            String expr = String.valueOf(sa.get("expression")).toLowerCase(Locale.ROOT);
            boolean uses = piiColumns.stream().anyMatch(pc -> Pattern.compile("\\b"
                    + Pattern.quote(String.valueOf(pc.get("column_name")).toLowerCase(Locale.ROOT)) + "\\b")
                    .matcher(expr).find());
            if (uses) {
                governance.addTagTarget(tagId, "sql_attribute", (String) sa.get("id"), user.id(), null, time.now());
            }
            semantic.patchSqlAttribute((String) sa.get("id"), Map.of("pii_processed", true));
        }
    }

    /** Whether a column carries the PII tag (NFR-SEC-04: no samples to external LLMs). */
    public boolean isPii(String columnId) {
        return governance.tagByName(TAG).map(t -> governance.hasTag((String) t.get("id"), "column", columnId))
                .orElse(false);
    }
}
