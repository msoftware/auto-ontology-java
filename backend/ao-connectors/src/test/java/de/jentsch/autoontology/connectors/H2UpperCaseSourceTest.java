// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

/** H2 in its default mode (upper-case identifiers). */
class H2UpperCaseSourceTest extends H2SourceTest {
    @Override
    protected boolean lower() {
        return false;
    }

    @Override
    void commentsAndQuotingFollowTheIdentifierCase() {
        // quoting expectations differ in upper-case mode; covered by the contract tests
    }
}
