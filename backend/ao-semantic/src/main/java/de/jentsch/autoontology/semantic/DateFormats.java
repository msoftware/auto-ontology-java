// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.semantic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Port of semantic/date_format.py: a notation is only reported when every sampled value parses with
 * it; indistinguishable day/month orders yield no format.
 */
public final class DateFormats {
    private static final int MAX_VALUES = 200;
    private static final int MAX_VALUE_LEN = 40;
    private static final List<String> DATE_TYPE_TOKENS = List.of("date", "time", "timestamp", "datetime");

    /** Candidate notation (pattern, label, kind). */
    private record Candidate(String pattern, String label, char kind) {}

    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate("uuuu-MM-dd HH:mm:ss.SSSSSS", "YYYY-MM-DD HH:MM:SS.f", 't'),
            new Candidate("uuuu-MM-dd'T'HH:mm:ss.SSSSSS", "YYYY-MM-DDTHH:MM:SS.f", 't'),
            new Candidate("uuuu-MM-dd HH:mm:ss", "YYYY-MM-DD HH:MM:SS", 't'),
            new Candidate("uuuu-MM-dd'T'HH:mm:ss", "YYYY-MM-DDTHH:MM:SS", 't'),
            new Candidate("uuuu-MM-dd HH:mm", "YYYY-MM-DD HH:MM", 't'),
            new Candidate("uuuu-MM-dd", "YYYY-MM-DD", 'd'),
            new Candidate("uuuu/MM/dd", "YYYY/MM/DD", 'd'),
            new Candidate("dd/MM/uuuu", "DD/MM/YYYY", 'd'),
            new Candidate("MM/dd/uuuu", "MM/DD/YYYY", 'd'),
            new Candidate("dd-MM-uuuu", "DD-MM-YYYY", 'd'),
            new Candidate("MM-dd-uuuu", "MM-DD-YYYY", 'd'),
            new Candidate("dd.MM.uuuu", "DD.MM.YYYY", 'd'),
            new Candidate("dd/MM/uu", "DD/MM/YY", 'd'),
            new Candidate("MM/dd/uu", "MM/DD/YY", 'd'),
            new Candidate("dd-MM-uu", "DD-MM-YY", 'd'),
            new Candidate("MM-dd-uu", "MM-DD-YY", 'd'),
            new Candidate("dd.MM.uu", "DD.MM.YY", 'd'),
            new Candidate("uuuuMMdd", "YYYYMMDD", 'd'),
            new Candidate("uuuuMM", "YYYYMM", 'm'),
            new Candidate("uuMMdd", "YYMMDD", 'd'),
            new Candidate("HH:mm:ss", "HH:MM:SS", 'h'),
            new Candidate("HH:mm", "HH:MM", 'h'),
            new Candidate("uuuu", "YYYY", 'y'));

    private static final List<Set<String>> INDISTINGUISHABLE = List.of(
            Set.of("DD/MM/YYYY", "MM/DD/YYYY"), Set.of("DD-MM-YYYY", "MM-DD-YYYY"), Set.of("DD/MM/YY", "MM/DD/YY"),
            Set.of("DD-MM-YY", "MM-DD-YY"), Set.of("DD.MM.YY", "MM.DD.YY"));

    private DateFormats() {}

    public static boolean isDateType(String dataType) {
        if (dataType == null) {
            return false;
        }
        String t = dataType.toLowerCase(Locale.ROOT);
        return DATE_TYPE_TOKENS.stream().anyMatch(t::contains);
    }

    /** The notation every sampled value follows, or null. */
    public static String infer(Iterable<?> values) {
        List<String> seen = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (Object v : values) {
            if (v == null) {
                continue;
            }
            String text = String.valueOf(v).trim();
            if (text.isEmpty() || text.length() > MAX_VALUE_LEN || !unique.add(text)) {
                continue;
            }
            seen.add(text);
            if (seen.size() >= MAX_VALUES) {
                break;
            }
        }
        if (seen.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (Candidate c : CANDIDATES) {
            if (parsesAll(c, seen)) {
                labels.add(c.label());
            }
        }
        if (labels.isEmpty()) {
            return null;
        }
        String winner = labels.get(0);
        for (Set<String> group : INDISTINGUISHABLE) {
            if (group.contains(winner) && labels.stream().filter(group::contains).count() > 1) {
                return null;
            }
        }
        return winner;
    }

    private static boolean parsesAll(Candidate c, List<String> values) {
        String pattern = c.pattern();
        boolean fraction = pattern.endsWith(".SSSSSS");
        DateTimeFormatter f = DateTimeFormatter.ofPattern(fraction ? pattern.substring(0, pattern.length() - 7) : pattern)
                .withResolverStyle(ResolverStyle.STRICT);
        for (String raw : values) {
            String v = raw;
            if (fraction) {
                int dot = v.lastIndexOf('.');
                if (dot < 0 || !v.substring(dot + 1).matches("\\d{1,9}")) {
                    return false;
                }
                v = v.substring(0, dot);
            }
            if (v.length() != f.format(sample(c.kind())).length() && c.kind() != 'y') {
                return false;
            }
            try {
                switch (c.kind()) {
                    case 't' -> {
                        LocalDateTime d = LocalDateTime.parse(v, f);
                        if (!plausible(d.getYear())) {
                            return false;
                        }
                    }
                    case 'd' -> {
                        LocalDate d = LocalDate.parse(v, f);
                        if (!plausible(d.getYear())) {
                            return false;
                        }
                    }
                    case 'm' -> {
                        if (!plausible(YearMonth.parse(v, f).getYear())) {
                            return false;
                        }
                    }
                    case 'h' -> LocalTime.parse(v, f);
                    default -> {
                        if (!v.matches("\\d{4}") || !plausible(Integer.parseInt(v))) {
                            return false;
                        }
                    }
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    private static java.time.temporal.TemporalAccessor sample(char kind) {
        return switch (kind) {
            case 't' -> LocalDateTime.of(2024, 11, 22, 13, 14, 15);
            case 'm' -> YearMonth.of(2024, 11);
            case 'h' -> LocalTime.of(13, 14, 15);
            default -> LocalDate.of(2024, 11, 22);
        };
    }

    private static boolean plausible(int year) {
        return year >= 1900 && year <= 2099;
    }
}
