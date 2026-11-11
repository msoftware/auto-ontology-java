// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** NFR-QD-05: one clock for prompts and time ranges. */
public final class AoClock {
    private AoClock() {}

    /** Builds the clock from {@code ao.clock.fixed-now}; system UTC when empty. */
    public static Clock from(String fixedNow) {
        if (fixedNow == null || fixedNow.isBlank()) {
            return Clock.systemUTC();
        }
        String v = fixedNow.trim();
        Instant instant = v.length() <= 10
                ? LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant()
                : Instant.parse(v);
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
