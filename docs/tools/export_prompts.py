"""Export the prompts of NVIDIA Auto-Ontology verbatim as resource files (section 0, item 6)."""
import ast
import importlib.util
import os
import re
import sys

SRC = sys.argv[1]           # .../auto-ontology/auto_ontology
AGENT = sys.argv[2]         # ao-agent/src/main/resources/prompts
SEM = sys.argv[3]           # ao-semantic/src/main/resources/prompts
os.makedirs(AGENT, exist_ok=True)
os.makedirs(SEM, exist_ok=True)


def write(folder, name, text, source):
    header = f"#! Derived from NVIDIA Auto-Ontology (Apache-2.0), {source}\n#! Verbatim copy; placeholders are {{{{name}}}}.\n"
    with open(os.path.join(folder, name + ".txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(header + text + "\n")


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def fmt_to_mustache(s):
    """str.format template -> {{name}} placeholders, {{ -> {."""
    out = re.sub(r"(?<!\{)\{([a-z_][a-z_0-9]*)\}(?!\})", r"<<\1>>", s)
    out = out.replace("{{", "{").replace("}}", "}")
    return re.sub(r"<<([a-z_0-9]+)>>", r"{{\1}}", out)


P = "retrieval/text_to_sql/prompts.py"
p = load(os.path.join(SRC, P), "aoprompts")
write(AGENT, "projection_rules", p._PROJECTION_RULES, P + " _PROJECTION_RULES")
write(AGENT, "projection_rules_short", p.format_projection_rules(True)[len(p._PROJECTION_RULES):],
      P + " format_projection_rules(shorten_answer=True)")
write(AGENT, "sql_user_prompt", fmt_to_mustache(p.create_sql_user_prompt), P + " create_sql_user_prompt")
write(AGENT, "dialect_sqlite", p._SQLITE_DIALECT_RULES, P + " _SQLITE_DIALECT_RULES")
write(AGENT, "dialect_snowflake", p._SNOWFLAKE_DIALECT_RULES, P + " _SNOWFLAKE_DIALECT_RULES")
write(AGENT, "dialect_postgres", p._POSTGRES_DIALECT_RULES, P + " _POSTGRES_DIALECT_RULES")
write(AGENT, "dialect_mysql", p._MYSQL_DIALECT_RULES, P + " _MYSQL_DIALECT_RULES")
write(AGENT, "sql_generation_output_spec", p._SQL_GENERATION_OUTPUT_SPEC, P + " _SQL_GENERATION_OUTPUT_SPEC")
for dialect, label in (("postgres", "schema"), ("sqlite", "bare")):
    for ex in (False, True):
        name = f"sql_from_candidates_{label}" + ("_examples" if ex else "")
        write(AGENT, name, p.create_sql_from_candidates_prompt(dialect=dialect, has_sql_examples=ex),
              P + f" create_sql_from_candidates_prompt(dialect={dialect!r}, has_sql_examples={ex})")
write(AGENT, "authoritative_evidence", p.format_authoritative_evidence("{{evidence}}"),
      P + " format_authoritative_evidence")
write(AGENT, "authoritative_evidence_first",
      p.format_authoritative_evidence("{{evidence}}", evidence_first_sql=True),
      P + " format_authoritative_evidence(evidence_first_sql=True)")
write(AGENT, "sql_general_prompt", p.create_sql_general_prompt, P + " create_sql_general_prompt")
write(AGENT, "intent_validation_system", p.INTENT_VALIDATION_SYSTEM_PROMPT, P + " INTENT_VALIDATION_SYSTEM_PROMPT")
write(AGENT, "intent_validation_system_joins_elsewhere",
      p.INTENT_VALIDATION_SYSTEM_PROMPT_JOINS_VALIDATED_ELSEWHERE,
      P + " INTENT_VALIDATION_SYSTEM_PROMPT_JOINS_VALIDATED_ELSEWHERE")
write(AGENT, "empty_like_check", p.create_empty_like_check_prompt("{{question_block}}", "{{sql_code}}"),
      P + " create_empty_like_check_prompt")
write(AGENT, "question_sanitization", p.create_question_sanitization_prompt("{{question}}"),
      P + " create_question_sanitization_prompt")
ent = p.create_entity_extraction_prompt("{{question}}").replace(
    f"1–{p.SQL_GEN_MAX_ENTITIES} noun phrases", "1–{{max_entities}} noun phrases")
write(AGENT, "entity_extraction", ent, P + " create_entity_extraction_prompt")
write(AGENT, "custom_analysis_relevance_filter", fmt_to_mustache(p.CUSTOM_ANALYSIS_RELEVANCE_FILTER_PROMPT),
      P + " CUSTOM_ANALYSIS_RELEVANCE_FILTER_PROMPT")
write(AGENT, "table_relevance_filter", fmt_to_mustache(p.TABLE_RELEVANCE_FILTER_PROMPT),
      P + " TABLE_RELEVANCE_FILTER_PROMPT")
write(AGENT, "follow_up_resolution",
      p.create_follow_up_resolution_prompt(question="{{question}}", conversation_history="{{conversation_history}}"),
      P + " create_follow_up_resolution_prompt")


def constants(path):
    tree = ast.parse(open(os.path.join(SRC, path), encoding="utf-8").read())
    out = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            try:
                v = ast.literal_eval(node.value)
            except Exception:
                continue
            if isinstance(v, str) and len(v) > 80:
                out[node.targets[0].id] = v
    return out


for path, folder, prefix in (
    ("semantic/term_extractor.py", SEM, "term_extractor"),
    ("semantic/fk_suggester.py", SEM, "fk_suggester"),
    ("semantic/semantic_fk.py", SEM, "semantic_fk"),
    ("semantic/sql_attribute_suggester.py", SEM, "sql_attribute_suggester"),
    ("semantic/bridge_tables.py", SEM, "bridge_tables"),
    ("semantic/visit_enter.py", SEM, "visit_enter"),
    ("retrieval/text_to_sql/agents/sql_reconstruction.py", AGENT, "sql_reconstruction"),
    ("retrieval/text_to_sql/agents/empty_result_value_repair.py", AGENT, "empty_result_value_repair"),
    ("retrieval/text_to_sql/agents/candidates_retrieval.py", AGENT, "candidates_retrieval"),
    ("retrieval/text_to_sql/agents/candidates_preparation.py", AGENT, "candidates_preparation"),
    ("retrieval/text_to_sql/agents/information_agent.py", AGENT, "information_agent"),
    ("retrieval/text_to_sql/agents/sql_parse_validation.py", AGENT, "sql_parse_validation"),
    ("retrieval/text_to_sql/visualization/prompts.py", AGENT, "visualization"),
    ("retrieval/entity_coverage/prompts.py", AGENT, "entity_coverage"),
    ("retrieval/entity_coverage/agents/coverage.py", AGENT, "entity_coverage_grade"),
):
    for name, value in constants(path).items():
        text = fmt_to_mustache(value) if re.search(r"(?<!{){[a-z_][a-z_0-9]*}(?!})", value) else value
        write(folder, f"{prefix}__{name.strip('_').lower()}", text, f"{path} {name}")

write(SEM, "system_prompt", open(os.path.join(SRC, "semantic/prompts/system_prompt.txt"), encoding="utf-8").read().strip(),
      "semantic/prompts/system_prompt.txt")
print("ok")
