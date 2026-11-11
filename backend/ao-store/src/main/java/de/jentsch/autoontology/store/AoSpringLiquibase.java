// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.resource.ResourceAccessor;

/**
 * Liquibase for the metadata store with its own changelog tables. Liquibase cannot find its
 * changelog table again in a persistent H2 store opened with {@code DATABASE_TO_LOWER=TRUE}
 * (it looks for the upper-case name), so the second start would fail with "table already exists".
 * This case is rejected up front with a clear message; in-memory H2 stores are not affected.
 */
public final class AoSpringLiquibase extends SpringLiquibase {

    public static final String CHANGELOG = "classpath:db/changelog/ao-changelog.yaml";

    public AoSpringLiquibase() {
        setChangeLog(CHANGELOG);
        setDatabaseChangeLogTable("AO_DATABASECHANGELOG");
        setDatabaseChangeLogLockTable("AO_DATABASECHANGELOGLOCK");
    }

    @Override
    protected Database createDatabase(Connection c, ResourceAccessor resourceAccessor) throws DatabaseException {
        try {
            String url = c.getMetaData().getURL();
            if (url != null && url.startsWith("jdbc:h2:") && !url.startsWith("jdbc:h2:mem:") && h2LowerCase(c)) {
                throw new DatabaseException("The Auto-Ontology store " + url.replaceAll(";.*", "")
                        + " is a persistent H2 database with DATABASE_TO_LOWER=TRUE; Liquibase cannot migrate it"
                        + " again after a restart. Remove DATABASE_TO_LOWER from spring.datasource.url"
                        + " (and delete the existing store file).");
            }
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
        return super.createDatabase(c, resourceAccessor);
    }

    private static boolean h2LowerCase(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS"
                        + " WHERE SETTING_NAME = 'DATABASE_TO_LOWER'")) {
            return rs.next() && "TRUE".equalsIgnoreCase(rs.getString(1));
        }
    }
}
