// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

/**
 * FR-CON-05: only a single {@code SELECT}/{@code WITH} statement may run on behalf of the agent
 * ({@code db_probe/executor.py::is_read_only_select}). The check walks the whole statement:
 * JSqlParser reports {@code WITH d AS (DELETE ... RETURNING *) SELECT * FROM d} as a plain
 * {@code Select}, so the type of the top-level statement alone proves nothing.
 */
public final class ReadOnlySql {
    private ReadOnlySql() {}

    /** Returns null when the statement is a single read-only SELECT, otherwise the reason. */
    public static String violation(String sql) {
        if (sql == null || sql.isBlank()) {
            return "empty SQL";
        }
        String cleaned = stripTrailingSemicolons(sql);
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(cleaned);
        } catch (Exception e) {
            return "SQL could not be parsed: " + firstLine(e.getMessage());
        }
        if (statements.size() != 1) {
            return "only a single statement is allowed";
        }
        Statement st = statements.get(0);
        if (!(st instanceof Select select)) {
            return "only SELECT/WITH statements are allowed";
        }
        return violation(select);
    }

    /** Recursive part: CTEs, set operations, parentheses and derived tables. */
    private static String violation(Select select) {
        if (select.getWithItemsList() != null) {
            for (WithItem<?> with : select.getWithItemsList()) {
                // data-modifying CTE (PostgreSQL): INSERT/UPDATE/DELETE ... RETURNING inside WITH
                if (!(with.getParenthesedStatement() instanceof ParenthesedSelect cte)) {
                    return "data-modifying statements in WITH are not allowed";
                }
                String v = violation(cte);
                if (v != null) {
                    return v;
                }
            }
        }
        if (select.getForMode() != null) {
            return "SELECT ... FOR UPDATE is not allowed";
        }
        if (select instanceof PlainSelect ps) {
            if (ps.getIntoTables() != null && !ps.getIntoTables().isEmpty()) {
                return "SELECT INTO is not allowed";
            }
            String v = violation(ps.getFromItem());
            if (v == null && ps.getJoins() != null) {
                for (Join join : ps.getJoins()) {
                    v = violation(join.getFromItem());
                    if (v != null) {
                        break;
                    }
                }
            }
            return v;
        }
        if (select instanceof ParenthesedSelect p) {
            return p.getSelect() == null ? null : violation(p.getSelect());
        }
        if (select instanceof SetOperationList list) {
            for (Select branch : list.getSelects()) {
                String v = violation(branch);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static String violation(FromItem item) {
        return item instanceof Select sub ? violation(sub) : null;
    }

    public static boolean isReadOnlySelect(String sql) {
        return violation(sql) == null;
    }

    public static String stripTrailingSemicolons(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    static String firstLine(String msg) {
        if (msg == null) {
            return "";
        }
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, nl) : msg;
    }
}
