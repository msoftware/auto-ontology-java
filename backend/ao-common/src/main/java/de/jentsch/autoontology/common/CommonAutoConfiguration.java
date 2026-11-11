// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;

/** Shared beans: properties, fixed user, clock, error handler. */
@AutoConfiguration
@EnableConfigurationProperties(AoProperties.class)
@Import(ApiExceptionHandler.class)
@PropertySource("classpath:ao-defaults.properties")
public class CommonAutoConfiguration {

    public CommonAutoConfiguration(
            AoProperties props, @Value("${quasar-dashboard.api-prefix:#{null}}") String dashboardPrefix) {
        ApiPrefixValidator.validate("ao.api-prefix", props.apiPrefix(), dashboardPrefix);
        ApiPrefixValidator.validate("ao.nl-chart.api-prefix", props.resolvedNlChartPrefix(), dashboardPrefix);
    }

    @Bean
    @ConditionalOnMissingBean
    public CurrentUser aoCurrentUser(AoProperties props) {
        return CurrentUser.fixed(props.user().id(), props.user().displayName());
    }

    /**
     * Uses the bean {@code quasarDashboardClock} when the dashboard runs in the same host
     * (NFR-QD-05); {@code ao.clock.fixed-now} wins when set.
     */
    @Bean
    @ConditionalOnMissingBean(name = "aoClock")
    public AoTime aoTime(AoProperties props, ObjectProvider<Clock> clocks) {
        if (props.clock().fixedNow() != null && !props.clock().fixedNow().isBlank()) {
            return new AoTime(AoClock.from(props.clock().fixedNow()));
        }
        return new AoTime(clocks.stream().findFirst().orElseGet(Clock::systemUTC));
    }
}
