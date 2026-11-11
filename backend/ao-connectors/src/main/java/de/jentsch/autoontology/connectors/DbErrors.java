// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.util.Locale;

/** FR-CON-06: normalized, short database errors for agent and UI (connectors/db_errors.py). */
public final class DbErrors {
    /** Error category. */
    public enum Kind {
        TIMEOUT,
        CONNECTION,
        SYNTAX,
        PERMISSION,
        OTHER
    }

    private DbErrors() {}

    public static Kind classify(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof SQLTimeoutException) {
                return Kind.TIMEOUT;
            }
            if (t instanceof SQLSyntaxErrorException) {
                return Kind.SYNTAX;
            }
            if (t instanceof SQLException se) {
                String state = se.getSQLState() == null ? "" : se.getSQLState();
                String msg = String.valueOf(se.getMessage()).toLowerCase(Locale.ROOT);
                if (state.startsWith("08")) {
                    return Kind.CONNECTION;
                }
                if (state.startsWith("42501") || msg.contains("permission denied") || msg.contains("insufficient privileges")
                        || msg.contains("not authorized") || state.equals("28000")) {
                    return Kind.PERMISSION;
                }
                if (state.equals("57014") || msg.contains("timeout") || msg.contains("timed out")
                        || msg.contains("canceled") || msg.contains("cancelled")) {
                    return Kind.TIMEOUT;
                }
                if (state.startsWith("42") || msg.contains("syntax")) {
                    return Kind.SYNTAX;
                }
            }
            t = t.getCause();
        }
        return Kind.OTHER;
    }

    /** One short line, without credentials. */
    public static String shortMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getMessage() == null) {
            t = t.getCause();
        }
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        msg = ConnectionSpec.redact(msg);
        int nl = msg.indexOf('\n');
        if (nl > 0) {
            msg = msg.substring(0, nl);
        }
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }

    /** A failed statement with its category. */
    public static class SourceQueryException extends RuntimeException {
        private final Kind kind;

        public SourceQueryException(Throwable cause) {
            super(shortMessage(cause), cause);
            this.kind = classify(cause);
        }

        public Kind kind() {
            return kind;
        }
    }
}
