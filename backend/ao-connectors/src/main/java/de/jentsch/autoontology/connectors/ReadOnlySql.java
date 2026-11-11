// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

/**
 * FR-CON-05: only a single {@code SELECT}/{@code WITH} statement may run on behalf of the agent
 * ({@code db_probe/executor.py::is_read_only_select}).
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
        if (select instanceof PlainSelect ps) {
            if (ps.getIntoTables() != null && !ps.getIntoTables().isEmpty()) {
                return "SELECT INTO is not allowed";
            }
            if (ps.getForMode() != null) {
                return "SELECT ... FOR UPDATE is not allowed";
            }
        }
        return null;
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
