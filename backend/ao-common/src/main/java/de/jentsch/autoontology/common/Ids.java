// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

import java.util.UUID;

/** FR-DB-02: text UUIDs; invalid IDs never cause a 500. */
public final class Ids {
    private Ids() {}

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static boolean isUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
