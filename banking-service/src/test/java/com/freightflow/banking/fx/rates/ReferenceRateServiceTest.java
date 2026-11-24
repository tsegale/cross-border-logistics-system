package com.freightflow.banking.fx.rates;

import com.freightflow.banking.fx.rates.ReferenceRateExceptions.FxRatesStaleException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which rate is due, when one is stale, and what is quarantined (ADR 0034), with the real ECB numbers. */
class ReferenceRateServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final ReferenceRateRepository rates = mock(ReferenceRateRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @ParameterizedTest(name = "{0} in Frankfurt: {1} is due")
    @CsvSource({
            // Before 17:00 on a working day the previous working day's rate is the newest due, after it today's.
            "2026-11-23T16:59, 2026-11-20",
            "2026-11-23T17:00, 2026-11-23",
            "2026-11-22T12:00, 2026-11-20",
            // Good Friday and Easter Monday 2026: Thursday's rate stays the newest.
            "2026-04-03T18:00, 2026-04-02",
            "2026-04-06T18:00, 2026-04-02",
            "2026-04-07T09:00, 2026-04-02",
            // Christmas: the 24th's rate until the 29th's is due.
            "2026-12-28T18:00, 2026-12-28",
            "2026-12-28T10:00, 2026-12-24",
            "2027-01-01T18:00, 2026-12-31"})
    void theNewestRateDueFollowsThePublicationTimeAndTheTargetCalendar(LocalDateTime frankfurt, LocalDate due) {
        assertThat(service(frankfurt).expectedValueDate()).isEqualTo(due);
    }

    @Test
    void aRateUpToTheMaximumAgeInWorkingDaysPrices() {
        ReferenceRateService service = service(LocalDateTime.parse("2026-11-23T18:00"));
        // Due: Monday the 23rd. Thursday the 19th is two working days behind it (20th, 23rd): allowed.
        ReferenceRate thursday = confirmed("2026-11-19", "18.9948");
        when(rates.findLatestConfirmed("ecb", "EUR", "ZAR")).thenReturn(Optional.of(thursday));

        assertThat(service.usableEurZar()).isSameAs(thursday);
        assertThat(service.ageInWorkingDays()).isEqualTo(2.0);
    }

    @Test
    void aRateBeyondTheMaximumAgeIsRefusedNotPriced() {
        ReferenceRateService service = service(LocalDateTime.parse("2026-11-23T18:00"));
        when(rates.findLatestConfirmed("ecb", "EUR", "ZAR")).thenReturn(Optional.of(confirmed("2026-11-18", "18.8897")));

        assertThatThrownBy(service::usableEurZar).isInstanceOf(FxRatesStaleException.class)
                .hasMessageContaining("2026-11-18").hasMessageContaining("2026-11-23")
                .satisfies(ex -> assertThat(((FxRatesStaleException) ex).getMaxAgeWorkingDays()).isEqualTo(2));
    }

    @Test
    void aLongWeekendDoesNotMakeARateStale() {
        // Easter Tuesday 2026, 18:00: Thursday's rate is one working day behind the Tuesday rate due.
        ReferenceRateService service = service(LocalDateTime.parse("2026-04-07T18:00"));
        when(rates.findLatestConfirmed("ecb", "EUR", "ZAR")).thenReturn(Optional.of(confirmed("2026-04-02", "19.6061")));

        assertThat(service.ageInWorkingDays()).isEqualTo(1.0);
        assertThat(service.usableEurZar().getValueDate()).isEqualTo(LocalDate.parse("2026-04-02"));
    }

    @Test
    void noRateAtAllIsStaleAndAnInfiniteAge() {
        ReferenceRateService service = service(LocalDateTime.parse("2026-11-23T18:00"));
        when(rates.findLatestConfirmed("ecb", "EUR", "ZAR")).thenReturn(Optional.empty());

        assertThatThrownBy(service::usableEurZar).isInstanceOf(FxRatesStaleException.class)
                .hasMessageContaining("No confirmed reference rate");
        assertThat(service.ageInWorkingDays()).isInfinite();
    }

    @Test
    void anOrdinaryDayIsConfirmed() {
        ReferenceRateService service = storing(LocalDateTime.parse("2026-11-23T18:00"), "2026-11-20");
        when(rates.findConfirmedBefore(eq("ecb"), eq("EUR"), eq("ZAR"), eq(LocalDate.parse("2026-11-23"))))
                .thenReturn(Optional.of(confirmed("2026-11-20", "19.1987")));

        List<ReferenceRate> stored = service.ingest(List.of(observation("2026-11-20", "19.1987"),
                observation("2026-11-23", "19.1513")), Optional.of(bon("2026-11-23", "19.1815")));

        assertThat(stored).singleElement().satisfies(rate -> {
            assertThat(rate.getValueDate()).isEqualTo(LocalDate.parse("2026-11-23"));
            assertThat(rate.getStatus()).isEqualTo(ReferenceRateStatus.CONFIRMED);
            assertThat(rate.getPreviousRate()).isEqualByComparingTo("19.1987");
            assertThat(rate.getCrossCheckSource()).isEqualTo("bank-of-namibia");
            assertThat(rate.getCrossCheckRate()).isEqualByComparingTo("19.1815");
            assertThat(rate.getQuarantineReason()).isNull();
        });
    }

    @Test
    void aMoveOverTheThresholdIsQuarantined() {
        ReferenceRateService service = storing(LocalDateTime.parse("2026-11-23T18:00"), "2026-11-20");
        when(rates.findConfirmedBefore(eq("ecb"), eq("EUR"), eq("ZAR"), any()))
                .thenReturn(Optional.of(confirmed("2026-11-20", "19.1987")));

        // 20.3 is 5.74% above the 20th's confirmed 19.1987.
        ReferenceRate rate = service.ingest(List.of(observation("2026-11-23", "20.3")), Optional.empty()).getFirst();

        assertThat(rate.getStatus()).isEqualTo(ReferenceRateStatus.QUARANTINED);
        assertThat(rate.getQuarantineReason()).contains("moved 5.74%").contains("19.19870000").contains("5.00%");
    }

    @Test
    void theRealEasterDropIsUnderTheThreshold() {
        // 8 April 2026: 19.093 after 19.5192 the day before, the largest move in the recorded fixtures, 2.18%.
        ReferenceRateService service = storing(LocalDateTime.parse("2026-04-08T18:00"), "2026-04-07");
        when(rates.findConfirmedBefore(eq("ecb"), eq("EUR"), eq("ZAR"), any()))
                .thenReturn(Optional.of(confirmed("2026-04-07", "19.5192")));

        ReferenceRate rate = service.ingest(List.of(observation("2026-04-08", "19.093")), Optional.empty()).getFirst();

        assertThat(rate.getStatus()).isEqualTo(ReferenceRateStatus.CONFIRMED);
    }

    @Test
    void aBankOfNamibiaRateOffThePegIsQuarantined() {
        ReferenceRateService service = storing(LocalDateTime.parse("2026-11-23T18:00"), "2026-11-20");
        when(rates.findConfirmedBefore(eq("ecb"), eq("EUR"), eq("ZAR"), any()))
                .thenReturn(Optional.of(confirmed("2026-11-20", "19.1987")));

        // 19.75 NAD per EUR against the ECB's 19.1513 ZAR per EUR implies 1.031262 NAD per ZAR, 3.1% off the 1:1 peg.
        ReferenceRate rate = service.ingest(List.of(observation("2026-11-23", "19.1513")),
                Optional.of(bon("2026-11-23", "19.75"))).getFirst();

        assertThat(rate.getStatus()).isEqualTo(ReferenceRateStatus.QUARANTINED);
        assertThat(rate.getQuarantineReason()).contains("off the 1:1 peg by 3.13%").contains("NAD/ZAR 1.031262");
    }

    @Test
    void aBankOfNamibiaRateForAnotherDayIsNotCompared() {
        ReferenceRateService service = storing(LocalDateTime.parse("2026-11-23T18:00"), "2026-11-20");
        when(rates.findConfirmedBefore(eq("ecb"), eq("EUR"), eq("ZAR"), any()))
                .thenReturn(Optional.of(confirmed("2026-11-20", "19.1987")));

        ReferenceRate rate = service.ingest(List.of(observation("2026-11-23", "19.1513")),
                Optional.of(bon("2026-11-20", "25.00"))).getFirst();

        assertThat(rate.getStatus()).isEqualTo(ReferenceRateStatus.CONFIRMED);
        assertThat(rate.getCrossCheckSource()).isNull();
    }

    @Test
    void aRateForAClosingDayIsQuarantined() {
        ReferenceRateService service = storing(LocalDateTime.parse("2026-11-23T18:00"), "2026-11-20");
        when(rates.findConfirmedBefore(eq("ecb"), eq("EUR"), eq("ZAR"), any()))
                .thenReturn(Optional.of(confirmed("2026-11-20", "19.1987")));

        ReferenceRate rate = service.ingest(List.of(observation("2026-11-21", "19.18")), Optional.empty()).getFirst();

        assertThat(rate.getStatus()).isEqualTo(ReferenceRateStatus.QUARANTINED);
        assertThat(rate.getQuarantineReason()).contains("2026-11-21 is not a TARGET working day");
    }

    @Test
    void aRateFromTheFutureIsQuarantined() {
        ReferenceRateService service = storing(LocalDateTime.parse("2026-11-23T12:00"), "2026-11-19");
        when(rates.findConfirmedBefore(eq("ecb"), eq("EUR"), eq("ZAR"), any()))
                .thenReturn(Optional.of(confirmed("2026-11-19", "18.9948")));

        // At noon the 23rd's rate is not due yet: the 20th's is the newest that can exist.
        List<ReferenceRate> stored = service.ingest(List.of(observation("2026-11-20", "19.1987"),
                observation("2026-11-23", "19.1513")), Optional.empty());

        assertThat(stored).extracting(ReferenceRate::getStatus)
                .containsExactly(ReferenceRateStatus.CONFIRMED, ReferenceRateStatus.QUARANTINED);
        assertThat(stored.getLast().getQuarantineReason()).contains("after the newest one due, 2026-11-20");
    }

    @Test
    void theFirstRateEverIsConfirmedOnlyWhenTheBankOfNamibiaBacksIt() {
        ReferenceRateService backed = storing(LocalDateTime.parse("2026-11-23T18:00"), null);
        when(rates.findConfirmedBefore(anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());

        List<ReferenceRate> stored = backed.ingest(List.of(observation("2026-11-20", "19.1987"),
                observation("2026-11-23", "19.1513")), Optional.of(bon("2026-11-23", "19.1815")));

        assertThat(stored).as("only the newest of a first fetch: nothing to judge the older ones by").singleElement()
                .satisfies(rate -> assertThat(rate.getStatus()).isEqualTo(ReferenceRateStatus.CONFIRMED));

        ReferenceRate alone = backed.ingest(List.of(observation("2026-11-23", "19.1513")), Optional.empty()).getFirst();
        assertThat(alone.getStatus()).isEqualTo(ReferenceRateStatus.QUARANTINED);
        assertThat(alone.getQuarantineReason()).contains("no earlier confirmed rate");
    }

    private ReferenceRateService service(LocalDateTime frankfurt) {
        Clock clock = Clock.fixed(frankfurt.atZone(BERLIN).toInstant(), BERLIN);
        return new ReferenceRateService(rates, jdbcTemplate, RateSourcesTest.properties(), new SimpleMeterRegistry(),
                clock);
    }

    private ReferenceRateService storing(LocalDateTime frankfurt, String newestStored) {
        when(rates.findNewestValueDate("ecb", "EUR", "ZAR"))
                .thenReturn(Optional.ofNullable(newestStored).map(LocalDate::parse));
        when(rates.saveAndFlush(any(ReferenceRate.class))).thenAnswer(call -> call.getArgument(0));
        return service(frankfurt);
    }

    private static ReferenceRate confirmed(String day, String rate) {
        return ReferenceRate.checked("ecb", "EUR", "ZAR", new BigDecimal(rate).setScale(8), LocalDate.parse(day),
                LocalDate.parse(day).atTime(16, 5).atZone(BERLIN).toInstant(), null, null, null);
    }

    private static EcbRates.Observation observation(String day, String rate) {
        return new EcbRates.Observation("ZAR", LocalDate.parse(day), new BigDecimal(rate));
    }

    private static BonRates.NadPerEur bon(String day, String rate) {
        return new BonRates.NadPerEur(LocalDate.parse(day), new BigDecimal(rate));
    }
}
