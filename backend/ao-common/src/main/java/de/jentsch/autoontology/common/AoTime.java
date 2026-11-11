// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Holder of the shared clock. A wrapper instead of a plain {@link Clock} bean so the host can
 * keep its own {@code Clock} beans without ambiguity.
 */
public record AoTime(Clock clock) {
    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
