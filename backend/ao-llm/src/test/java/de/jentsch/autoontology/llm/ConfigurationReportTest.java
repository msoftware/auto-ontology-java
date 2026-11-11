// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigurationReportTest {

    @Test
    void keyShowsTenCharactersAtMostHalf() {
        assertThat(ConfigurationReport.maskKey("sk-test-0123456789abcdef0123456789"))
                .isEqualTo("sk-test-01… (34 chars)");
        assertThat(ConfigurationReport.maskKey("abcdef")).isEqualTo("abc… (6 chars)");
        assertThat(ConfigurationReport.maskKey("")).isEqualTo("-");
        assertThat(ConfigurationReport.maskKey(null)).isEqualTo("-");
    }

    @Test
    void urlPasswordsAreMasked() {
        assertThat(ConfigurationReport.maskUrl("postgresql://ao:dummy@db:5432/sales"))
                .isEqualTo("postgresql://ao:***@db:5432/sales");
        assertThat(ConfigurationReport.maskUrl("jdbc:sqlserver://h;user=sa;password=dummy;db=x"))
                .isEqualTo("jdbc:sqlserver://h;user=sa;password=***;db=x");
        assertThat(ConfigurationReport.maskUrl("jdbc:h2:mem:demo_sales")).isEqualTo("jdbc:h2:mem:demo_sales");
    }
}
