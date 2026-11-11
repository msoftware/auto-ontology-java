// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

/**
 * FR-AUTH-01: the user every request runs as. The project has no authentication; this bean is the
 * single place a later login could plug into.
 */
public interface CurrentUser {
    String id();

    String displayName();

    static CurrentUser fixed(String id, String displayName) {
        return new CurrentUser() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return displayName;
            }
        };
    }
}
