package com.freightflow.banking.fx.rates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TargetCalendarTest {

    /** Good Friday and Easter Monday as the ECB's own calendar lists them for 2026 to 2028. */
    @ParameterizedTest
    @CsvSource({"2025, 2025-04-20", "2026, 2026-04-05", "2027, 2027-03-28", "2028, 2028-04-16"})
    void easterSundayMatchesTheEcbCalendar(int year, LocalDate easter) {
        assertThat(TargetCalendar.easterSunday(year)).isEqualTo(easter);
        assertThat(TargetCalendar.isWorkingDay(easter.minusDays(2))).as("Good Friday").isFalse();
        assertThat(TargetCalendar.isWorkingDay(easter.plusDays(1))).as("Easter Monday").isFalse();
        assertThat(TargetCalendar.isWorkingDay(easter.plusDays(2))).as("the Tuesday after").isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01-01", "2026-05-01", "2026-12-25", "2026-12-26", "2026-11-21", "2026-11-22"})
    void closingDaysAndWeekendsAreNotWorkingDays(LocalDate day) {
        assertThat(TargetCalendar.isWorkingDay(day)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-12-24", "2025-12-31", "2026-11-23"})
    void christmasEveAndNewYearsEveAreWorkingDays(LocalDate day) {
        assertThat(TargetCalendar.isWorkingDay(day)).isTrue();
    }

    /**
     * The recorded ECB responses (src/test/resources/ecb) have a rate for every working day of their range and none
     * for any other day: the calendar agrees with what the ECB actually published around Christmas 2025 and Easter
     * 2026.
     */
    @ParameterizedTest
    @CsvSource({"zar-christmas-2025.csv, 2025-12-19, 2026-01-06", "zar-easter-2026.csv, 2026-04-01, 2026-04-08",
            "zar-latest-5.csv, 2026-11-17, 2026-11-23"})
    void theCalendarMatchesTheDaysTheEcbPublished(String fixture, LocalDate from, LocalDate to) throws IOException {
        List<LocalDate> published = EcbRates.parse(read("ecb/" + fixture), "ZAR").stream()
                .map(EcbRates.Observation::valueDate).toList();
        List<LocalDate> workingDays = Stream.iterate(from, day -> !day.isAfter(to), day -> day.plusDays(1))
                .filter(TargetCalendar::isWorkingDay).toList();
        assertThat(published).isEqualTo(workingDays);
    }

    @Test
    void workingDaysBetweenSkipsClosingDays() {
        // Wednesday 24 December 2025 to Monday 29 December: 25, 26 closed, 27, 28 a weekend.
        assertThat(TargetCalendar.workingDaysBetween(LocalDate.parse("2025-12-24"), LocalDate.parse("2025-12-29")))
                .isEqualTo(1);
        assertThat(TargetCalendar.workingDaysBetween(LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-23")))
                .isZero();
        assertThat(TargetCalendar.workingDaysBetween(LocalDate.parse("2026-11-24"), LocalDate.parse("2026-11-23")))
                .isZero();
        assertThat(TargetCalendar.onOrBefore(LocalDate.parse("2026-04-06"))).isEqualTo(LocalDate.parse("2026-04-02"));
    }

    static String read(String resource) throws IOException {
        try (InputStream in = TargetCalendarTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(Objects.requireNonNull(in, resource).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
