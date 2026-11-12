// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import de.jentsch.autoontology.connectors.ReadOnlySql;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.util.TablesNamesFinder;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;

/**
 * Parses observed SQL (catalog/sql_parse.py with sqlglot in the original; JSqlParser here):
 * referenced tables and columns, equi-joins and union pairs, plus the counters stored on
 * {@code sql_query}.
 */
public final class QueryHistoryParser {
    private QueryHistoryParser() {}

    /** A column reference resolved to {@code schema.table.column} (schema may be null). */
    public record ColumnRef(String schema, String table, String column) {}

    /** An equi-join or union pair between two columns. */
    public record ColumnPair(ColumnRef left, ColumnRef right, String condition) {}

    /** Parsed statement. */
    public record Parsed(String sql, String hash, Set<String> tables, Set<ColumnRef> columns, List<ColumnPair> joins,
            List<ColumnPair> unions, int nodes) {}

    /** Collapses whitespace and strips trailing semicolons; basis of the text hash. */
    public static String normalize(String sql) {
        return ReadOnlySql.stripTrailingSemicolons(sql.replaceAll("\\s+", " ").trim());
    }

    public static String hash(String normalizedSql) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(normalizedSql.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns null when the statement is not a parsable SELECT (DDL/DML in the history is ignored). */
    public static Parsed parse(String raw) {
        String sql = normalize(raw);
        if (!ReadOnlySql.isReadOnlySelect(sql)) {
            return null;
        }
        Statement st;
        try {
            st = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            return null;
        }
        Set<String> tables = new LinkedHashSet<>();
        for (String t : new TablesNamesFinder<>().getTables(st)) {
            tables.add(t.replace("\"", "").replace("[", "").replace("]", ""));
        }
        Set<ColumnRef> columns = new LinkedHashSet<>();
        List<ColumnPair> joins = new ArrayList<>();
        List<ColumnPair> unions = new ArrayList<>();
        int[] nodes = {0};
        visit((Select) st, columns, joins, unions, nodes);
        return new Parsed(sql, hash(sql), tables, columns, joins, unions, nodes[0]);
    }

    private static void visit(Select select, Set<ColumnRef> columns, List<ColumnPair> joins, List<ColumnPair> unions,
            int[] nodes) {
        if (select instanceof SetOperationList sol) {
            List<List<ColumnRef>> projections = new ArrayList<>();
            for (Select s : sol.getSelects()) {
                visit(s, columns, joins, unions, nodes);
                projections.add(s instanceof PlainSelect ps ? projected(ps) : List.of());
            }
            // union pairs: same position of the projection in the first and every further branch
            for (int b = 1; b < projections.size(); b++) {
                List<ColumnRef> first = projections.get(0);
                List<ColumnRef> other = projections.get(b);
                for (int i = 0; i < Math.min(first.size(), other.size()); i++) {
                    if (first.get(i) != null && other.get(i) != null) {
                        unions.add(new ColumnPair(first.get(i), other.get(i), "UNION"));
                    }
                }
            }
            return;
        }
        if (select instanceof ParenthesedSelect p) {
            visit(p.getSelect(), columns, joins, unions, nodes);
            return;
        }
        if (!(select instanceof PlainSelect ps)) {
            return;
        }
        if (ps.getWithItemsList() != null) {
            ps.getWithItemsList().forEach(w -> {
                if (w.getParenthesedStatement() instanceof ParenthesedSelect cte) {
                    visit(cte, columns, joins, unions, nodes);
                }
            });
        }
        Map<String, Table> aliases = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Table> fromTables = new ArrayList<>();
        register(ps.getFromItem(), aliases, fromTables, columns, joins, unions, nodes);
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                register(j.getFromItem(), aliases, fromTables, columns, joins, unions, nodes);
            }
        }
        nodes[0] += 1 + (ps.getSelectItems() == null ? 0 : ps.getSelectItems().size())
                + (ps.getJoins() == null ? 0 : ps.getJoins().size());
        Table single = fromTables.size() == 1 ? fromTables.get(0) : null;
        // every column reference anywhere in the statement
        ExpressionDeParser collector = new ExpressionDeParser() {
            @Override
            public <S> StringBuilder visit(Column column, S context) {
                ColumnRef ref = resolve(column, aliases, single);
                if (ref != null) {
                    columns.add(ref);
                }
                return super.visit(column, context);
            }
        };
        ps.getSelectItems().forEach(si -> si.getExpression().accept(collector, null));
        if (ps.getWhere() != null) {
            ps.getWhere().accept(collector, null);
        }
        if (ps.getGroupBy() != null && ps.getGroupBy().getGroupByExpressionList() != null) {
            for (Object e : ps.getGroupBy().getGroupByExpressionList()) {
                ((Expression) e).accept(collector, null);
            }
        }
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                for (Expression on : j.getOnExpressions()) {
                    on.accept(collector, null);
                    equiJoins(on, aliases, single, joins);
                }
            }
        }
        if (ps.getWhere() != null) {
            equiJoins(ps.getWhere(), aliases, single, joins);
        }
    }

    private static void register(FromItem item, Map<String, Table> aliases, List<Table> fromTables,
            Set<ColumnRef> columns, List<ColumnPair> joins, List<ColumnPair> unions, int[] nodes) {
        if (item instanceof Table t) {
            fromTables.add(t);
            aliases.put(t.getName(), t);
            if (t.getAlias() != null) {
                aliases.put(t.getAlias().getName(), t);
            }
        } else if (item instanceof ParenthesedSelect p) {
            visit(p.getSelect(), columns, joins, unions, nodes);
        }
    }

    private static List<ColumnRef> projected(PlainSelect ps) {
        Map<String, Table> aliases = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Table> from = new ArrayList<>();
        if (ps.getFromItem() instanceof Table t) {
            from.add(t);
            aliases.put(t.getName(), t);
            if (t.getAlias() != null) {
                aliases.put(t.getAlias().getName(), t);
            }
        }
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                if (j.getFromItem() instanceof Table t) {
                    from.add(t);
                    aliases.put(t.getName(), t);
                    if (t.getAlias() != null) {
                        aliases.put(t.getAlias().getName(), t);
                    }
                }
            }
        }
        Table single = from.size() == 1 ? from.get(0) : null;
        List<ColumnRef> out = new ArrayList<>();
        ps.getSelectItems().forEach(si -> out.add(si.getExpression() instanceof Column c ? resolve(c, aliases, single) : null));
        return out;
    }

    private static void equiJoins(Expression e, Map<String, Table> aliases, Table single, List<ColumnPair> out) {
        if (e instanceof AndExpression and) {
            equiJoins(and.getLeftExpression(), aliases, single, out);
            equiJoins(and.getRightExpression(), aliases, single, out);
        } else if (e instanceof EqualsTo eq && eq.getLeftExpression() instanceof Column l
                && eq.getRightExpression() instanceof Column r) {
            ColumnRef a = resolve(l, aliases, single);
            ColumnRef b = resolve(r, aliases, single);
            if (a != null && b != null && !(a.table().equalsIgnoreCase(b.table())
                    && String.valueOf(a.schema()).equalsIgnoreCase(String.valueOf(b.schema())))) {
                out.add(new ColumnPair(a, b, eq.toString()));
            }
        }
    }

    private static ColumnRef resolve(Column c, Map<String, Table> aliases, Table single) {
        Table owner;
        if (c.getTable() != null && c.getTable().getName() != null) {
            owner = aliases.get(unquote(c.getTable().getName()));
            if (owner == null) {
                owner = aliases.get(c.getTable().getName());
            }
        } else {
            owner = single;
        }
        if (owner == null) {
            return null;
        }
        return new ColumnRef(owner.getSchemaName() == null ? null : unquote(owner.getSchemaName()),
                unquote(owner.getName()), unquote(c.getColumnName()));
    }

    static String unquote(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("[") && t.endsWith("]"))
                || (t.startsWith("`") && t.endsWith("`"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** Case-insensitive key for matching parsed names against the catalog. */
    public static String key(String schema, String table) {
        return (schema == null ? "" : schema.toLowerCase(Locale.ROOT)) + "." + table.toLowerCase(Locale.ROOT);
    }
}
