// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.connectors.ReadOnlySql;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import net.sf.jsqlparser.util.deparser.StatementDeParser;

/**
 * Static phase of validate_sql_query (agents/sql_parse_validation.py): read-only single SELECT,
 * parsable, catalog names exist, no degenerate predicate such as {@code 1=0}, mixed-case
 * identifiers quoted on PostgreSQL. Also reports the used objects for the intent prompt.
 */
public final class SqlStaticChecks {
    private SqlStaticChecks() {}

    /** Catalog view of the candidate database: table key → column names. */
    public record CatalogView(Map<String, Set<String>> columnsByTable, String dialect) {
        static String key(String schema, String table) {
            return (schema == null ? "" : schema.toLowerCase(Locale.ROOT)) + "." + table.toLowerCase(Locale.ROOT);
        }
    }

    /** Errors (empty = valid) and the referenced tables/columns. */
    public record Result(List<String> errors, Set<String> tables, Set<String> columns) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static Result check(String sql, CatalogView catalog) {
        List<String> errors = new ArrayList<>();
        Set<String> usedTables = new LinkedHashSet<>();
        Set<String> usedColumns = new LinkedHashSet<>();
        String violation = ReadOnlySql.violation(sql);
        if (violation != null) {
            errors.add(violation);
            return new Result(errors, usedTables, usedColumns);
        }
        Statement st;
        try {
            st = CCJSqlParserUtil.parse(ReadOnlySql.stripTrailingSemicolons(sql));
        } catch (Exception e) {
            errors.add("SQL could not be parsed: " + e.getMessage().lines().findFirst().orElse(""));
            return new Result(errors, usedTables, usedColumns);
        }
        Set<String> cteNames = new java.util.HashSet<>();
        if (st instanceof Select s && s.getWithItemsList() != null) {
            s.getWithItemsList().forEach(w -> cteNames.add(w.getAliasName().toLowerCase(Locale.ROOT)));
        }
        Map<String, String> aliasToKey = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> knownAliases = new java.util.HashSet<>();
        for (String raw : new TablesNamesFinder<>().getTables(st)) {
            String[] parts = unquote(raw).split("\\.");
            String table = parts[parts.length - 1];
            String schema = parts.length >= 2 ? parts[parts.length - 2] : null;
            if (schema == null && cteNames.contains(table.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String key = resolveTable(catalog, schema, table);
            if (key == null) {
                errors.add("Table " + raw + " does not exist in the available schema.");
            } else {
                usedTables.add(key);
            }
        }
        // alias map and column references
        List<Column> columns = new ArrayList<>();
        List<String> degenerate = new ArrayList<>();
        ExpressionDeParser expr = new ExpressionDeParser() {
            @Override
            public <S> StringBuilder visit(Column column, S context) {
                columns.add(column);
                return super.visit(column, context);
            }

            @Override
            public <S> StringBuilder visit(EqualsTo eq, S context) {
                if (eq.getLeftExpression() instanceof LongValue l && eq.getRightExpression() instanceof LongValue r
                        && l.getValue() != r.getValue()) {
                    degenerate.add(eq.toString());
                }
                return super.visit(eq, context);
            }
        };
        SelectDeParser select = new SelectDeParser(expr, new StringBuilder()) {
            @Override
            public <S> StringBuilder visit(Table table, S context) {
                String key = resolveTable(catalog, table.getSchemaName() == null ? null : unquote(table.getSchemaName()),
                        unquote(table.getName()));
                if (key != null) {
                    aliasToKey.put(unquote(table.getName()), key);
                    if (table.getAlias() != null) {
                        aliasToKey.put(table.getAlias().getName(), key);
                    }
                } else if (table.getAlias() != null) {
                    knownAliases.add(table.getAlias().getName().toLowerCase(Locale.ROOT));
                }
                return super.visit(table, context);
            }
        };
        expr.setSelectVisitor(select);
        expr.setBuilder(select.getBuilder());
        try {
            st.accept(new StatementDeParser(expr, select, new StringBuilder()), null);
        } catch (RuntimeException e) {
            // deparsing is best effort; the parse succeeded
        }
        for (String d : degenerate) {
            errors.add("Degenerate predicate " + d + " makes the query return no rows.");
        }
        Set<String> selectAliases = selectAliases(sql);
        for (Column c : columns) {
            String name = unquote(c.getColumnName());
            String owner = c.getTable() == null || c.getTable().getName() == null ? null : unquote(c.getTable().getName());
            if (owner != null) {
                String key = aliasToKey.get(owner);
                if (key == null) {
                    if (!knownAliases.contains(owner.toLowerCase(Locale.ROOT)) && !cteNames.contains(owner.toLowerCase(Locale.ROOT))
                            && !aliasToKey.isEmpty() && !isSubqueryAlias(sql, owner)) {
                        errors.add("Alias or table " + owner + " used for column " + name + " is not defined in FROM/JOIN.");
                    }
                    continue;
                }
                Set<String> cols = catalog.columnsByTable().get(key);
                if (cols != null && cols.stream().noneMatch(x -> x.equalsIgnoreCase(name))) {
                    errors.add("Column " + name + " does not exist in table " + key.substring(key.indexOf('.') + 1) + ".");
                } else {
                    usedColumns.add(key + "." + name);
                    quoting(catalog, c, key, name, errors);
                }
            } else {
                boolean found = false;
                for (String key : new LinkedHashSet<>(aliasToKey.values())) {
                    Set<String> cols = catalog.columnsByTable().get(key);
                    if (cols != null && cols.stream().anyMatch(x -> x.equalsIgnoreCase(name))) {
                        usedColumns.add(key + "." + name);
                        quoting(catalog, c, key, name, errors);
                        found = true;
                    }
                }
                if (!found && !aliasToKey.isEmpty() && !selectAliases.contains(name.toLowerCase(Locale.ROOT))
                        && !name.equalsIgnoreCase("true") && !name.equalsIgnoreCase("false")
                        && cteNames.isEmpty() && knownAliases.isEmpty()) {
                    errors.add("Column " + name + " does not exist in any referenced table.");
                }
            }
        }
        return new Result(new ArrayList<>(new LinkedHashSet<>(errors)), usedTables, usedColumns);
    }

    private static void quoting(CatalogView catalog, Column c, String key, String name, List<String> errors) {
        if (!"postgres".equals(catalog.dialect())) {
            return;
        }
        String stored = catalog.columnsByTable().get(key).stream().filter(x -> x.equalsIgnoreCase(name)).findFirst()
                .orElse(name);
        boolean quoted = c.getColumnName().startsWith("\"");
        if (!quoted && !stored.equals(stored.toLowerCase(Locale.ROOT))) {
            errors.add("Column " + stored + " contains uppercase letters and must be double-quoted on PostgreSQL: \""
                    + stored + "\".");
        }
    }

    private static boolean isSubqueryAlias(String sql, String alias) {
        return sql.matches("(?is).*\\)\\s*(AS\\s+)?" + java.util.regex.Pattern.quote(alias) + "\\b.*");
    }

    private static Set<String> selectAliases(String sql) {
        Set<String> out = new java.util.HashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\bAS\\s+\"?([A-Za-z_][A-Za-z0-9_]*)\"?")
                .matcher(sql);
        while (m.find()) {
            out.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    static String resolveTable(CatalogView catalog, String schema, String table) {
        if (schema != null) {
            String key = CatalogView.key(schema, table);
            return catalog.columnsByTable().containsKey(key) ? key : null;
        }
        String found = null;
        for (String key : catalog.columnsByTable().keySet()) {
            if (key.endsWith("." + table.toLowerCase(Locale.ROOT))) {
                found = key;
            }
        }
        return found;
    }

    static String unquote(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String part : s.split("\\.")) {
            String p = part.strip();
            if ((p.startsWith("\"") && p.endsWith("\"")) || (p.startsWith("[") && p.endsWith("]"))
                    || (p.startsWith("`") && p.endsWith("`"))) {
                p = p.substring(1, p.length() - 1);
            }
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append(p);
        }
        return out.toString();
    }

    /** Builds the catalog view from column rows (table key → names). */
    public static CatalogView view(List<Map<String, Object>> columnRows, String dialect) {
        Map<String, Set<String>> m = new HashMap<>();
        for (Map<String, Object> c : columnRows) {
            m.computeIfAbsent(CatalogView.key((String) c.get("schema_name"), (String) c.get("table_name")),
                    k -> new LinkedHashSet<>()).add((String) c.get("name"));
        }
        return new CatalogView(m, dialect);
    }
}
