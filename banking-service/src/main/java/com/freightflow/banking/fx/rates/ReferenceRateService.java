package com.freightflow.banking.fx.rates;

import com.freightflow.banking.fx.rates.ReferenceRateExceptions.FxRatesStaleException;
import com.freightflow.banking.fx.rates.ReferenceRateExceptions.ReferenceRateNotFoundException;
import com.freightflow.banking.fx.rates.ReferenceRateExceptions.ReferenceRateNotQuarantinedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Daily reference rates (ADR 0034): which one is due, which one prices, and the checks a new one must pass.
 *
 * <p>The ECB publishes EUR/ZAR; NAD is ZAR under the Common Monetary Area's 1:1 peg, and the Bank of Namibia's own
 * NAD per EUR is the cross-check of that peg. A new rate is quarantined, not used, when it is for a day that is not a
 * TARGET working day or is after the newest one due, when it moved more than {@code max-daily-move} from the confirmed
 * rate before it, when the Bank of Namibia's rate for the same day implies a NAD/ZAR off the peg by more than
 * {@code peg-tolerance}, or when it is the very first rate and there is no second source for its day to back it.
 */
public class ReferenceRateService {

    public static final String BASE = "EUR";
    public static final String ZAR = "ZAR";
    private static final Logger log = LoggerFactory.getLogger(ReferenceRateService.class);
    private static final MathContext RATIO = new MathContext(20, RoundingMode.HALF_EVEN);

    private final ReferenceRateRepository rates;
    private final JdbcTemplate jdbcTemplate;
    private final ReferenceRateProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public ReferenceRateService(ReferenceRateRepository rates, JdbcTemplate jdbcTemplate,
                                ReferenceRateProperties properties, MeterRegistry meters, Clock clock) {
        this.rates = rates;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * The newest value date that should have a rate by now: today once the ECB's publication time and the grace
     * after it have passed on a TARGET working day, otherwise the working day before.
     */
    public LocalDate expectedValueDate() {
        ZonedDateTime now = clock.instant().atZone(properties.zone());
        LocalDate today = now.toLocalDate();
        boolean publishedToday = TargetCalendar.isWorkingDay(today)
                && !now.toLocalTime().isBefore(properties.publicationTime().plus(properties.publicationGrace()));
        return publishedToday ? today : TargetCalendar.onOrBefore(today.minusDays(1));
    }

    /** Whether a refresh would find anything new: no rate yet, or the newest stored is before the one due. */
    @Transactional(readOnly = true)
    public boolean refreshDue() {
        return rates.findNewestValueDate(EcbRates.SOURCE, BASE, ZAR)
                .map(newest -> newest.isBefore(expectedValueDate()))
                .orElse(true);
    }

    /** The confirmed EUR/ZAR rate that prices now, or a stale-rates refusal. */
    @Transactional(readOnly = true)
    public ReferenceRate usableEurZar() {
        LocalDate expected = expectedValueDate();
        Optional<ReferenceRate> latest = rates.findLatestConfirmed(EcbRates.SOURCE, BASE, ZAR);
        if (latest.isEmpty()) {
            throw new FxRatesStaleException(BASE + "/" + ZAR, null, expected, properties.maxAgeWorkingDays());
        }
        ReferenceRate rate = latest.get();
        if (TargetCalendar.workingDaysBetween(rate.getValueDate(), expected) > properties.maxAgeWorkingDays()) {
            throw new FxRatesStaleException(BASE + "/" + ZAR, rate.getValueDate(), expected,
                    properties.maxAgeWorkingDays());
        }
        return rate;
    }

    /** Working days the newest confirmed rate is behind the one due; infinite when there is none. For the gauge. */
    @Transactional(readOnly = true)
    public double ageInWorkingDays() {
        return rates.findLatestConfirmed(EcbRates.SOURCE, BASE, ZAR)
                .map(rate -> (double) TargetCalendar.workingDaysBetween(rate.getValueDate(), expectedValueDate()))
                .orElse(Double.POSITIVE_INFINITY);
    }

    /**
     * Stores the observations newer than the newest one stored, oldest first, each judged against the confirmed rate
     * before it. On a first run only the newest observation is taken: there is nothing to judge the older ones by.
     * Instances refreshing at once queue on a transaction-scoped advisory lock and the second finds nothing new.
     */
    @Transactional
    public List<ReferenceRate> ingest(List<EcbRates.Observation> observations, Optional<BonRates.NadPerEur> bon) {
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('reference-rates', 0))::text",
                String.class);
        Optional<LocalDate> newest = rates.findNewestValueDate(EcbRates.SOURCE, BASE, ZAR);
        List<EcbRates.Observation> fresh = observations.stream()
                .filter(observation -> newest.map(observation.valueDate()::isAfter).orElse(true))
                .toList();
        if (newest.isEmpty() && fresh.size() > 1) {
            fresh = List.of(fresh.getLast());
        }
        LocalDate expected = expectedValueDate();
        List<ReferenceRate> stored = new ArrayList<>();
        for (EcbRates.Observation observation : fresh) {
            ReferenceRate saved = rates.saveAndFlush(judge(observation, bon, expected));
            stored.add(saved);
            String status = saved.getStatus().name().toLowerCase(Locale.ROOT);
            afterCommit(() -> meters.counter("freightflow.fx.reference_rates.stored", "status", status).increment());
            if (saved.getStatus() == ReferenceRateStatus.QUARANTINED) {
                log.warn("Reference rate {} {}/{} {} for {} quarantined: {}", saved.getId(), BASE, ZAR,
                        saved.getRate(), saved.getValueDate(), saved.getQuarantineReason());
            } else {
                log.info("Reference rate {} {}/{} {} for {} confirmed", saved.getId(), BASE, ZAR, saved.getRate(),
                        saved.getValueDate());
            }
        }
        return stored;
    }

    private ReferenceRate judge(EcbRates.Observation observation, Optional<BonRates.NadPerEur> bon,
                                LocalDate expected) {
        LocalDate day = observation.valueDate();
        BigDecimal rate = observation.rate().setScale(8, RoundingMode.HALF_EVEN);
        List<String> reasons = new ArrayList<>();
        if (!TargetCalendar.isWorkingDay(day)) {
            reasons.add("value date " + day + " is not a TARGET working day");
        }
        if (day.isAfter(expected)) {
            reasons.add("value date " + day + " is after the newest one due, " + expected);
        }
        Optional<ReferenceRate> previous = rates.findConfirmedBefore(EcbRates.SOURCE, BASE, ZAR, day);
        previous.ifPresent(before -> {
            BigDecimal move = rate.divide(before.getRate(), RATIO).subtract(BigDecimal.ONE).abs();
            if (move.compareTo(properties.maxDailyMove()) > 0) {
                reasons.add("moved " + percent(move) + " from the confirmed rate " + before.getRate().toPlainString()
                        + " of " + before.getValueDate() + ", more than " + percent(properties.maxDailyMove()));
            }
        });
        ReferenceRate.CrossCheck crossCheck = null;
        Optional<BonRates.NadPerEur> sameDay = bon.filter(table -> table.valueDate().equals(day));
        if (sameDay.isPresent()) {
            BigDecimal nadPerEur = sameDay.get().rate();
            crossCheck = new ReferenceRate.CrossCheck(BonRates.SOURCE, nadPerEur.setScale(8, RoundingMode.HALF_EVEN));
            BigDecimal nadPerZar = nadPerEur.divide(rate, RATIO);
            BigDecimal offPeg = nadPerZar.subtract(BigDecimal.ONE).abs();
            if (offPeg.compareTo(properties.pegTolerance()) > 0) {
                reasons.add("the Bank of Namibia's " + nadPerEur.toPlainString() + " NAD per EUR implies NAD/ZAR "
                        + nadPerZar.setScale(6, RoundingMode.HALF_EVEN).toPlainString() + ", off the 1:1 peg by "
                        + percent(offPeg) + ", more than " + percent(properties.pegTolerance()));
            }
        } else if (previous.isEmpty()) {
            reasons.add("no earlier confirmed rate to compare it with and no Bank of Namibia rate for " + day);
        }
        return ReferenceRate.checked(EcbRates.SOURCE, BASE, ZAR, rate, day, clock.instant(),
                previous.map(ReferenceRate::getRate).orElse(null), crossCheck,
                reasons.isEmpty() ? null : String.join("; ", reasons));
    }

    /** A rate that a RATE_CONFIRMATION approval may be asked for. */
    @Transactional(readOnly = true)
    public ReferenceRate requireQuarantined(UUID id) {
        ReferenceRate rate = rates.findById(id).orElseThrow(() -> new ReferenceRateNotFoundException(id));
        if (rate.getStatus() != ReferenceRateStatus.QUARANTINED) {
            throw new ReferenceRateNotQuarantinedException(id, rate.getStatus());
        }
        return rate;
    }

    /** The second operator's decision on a quarantined rate, in the approval's transaction. */
    @Transactional
    public ReferenceRate decide(UUID id, boolean confirmed, UUID approvalId) {
        ReferenceRate rate = rates.findByIdForUpdate(id).orElseThrow(() -> new ReferenceRateNotFoundException(id));
        rate.decide(confirmed, approvalId, clock.instant());
        ReferenceRate saved = rates.saveAndFlush(rate);
        log.info("Reference rate {} {} by approval {}", id, confirmed ? "confirmed" : "rejected", approvalId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<ReferenceRate> find(UUID id) {
        return rates.findById(id);
    }

    @Transactional(readOnly = true)
    public List<ReferenceRate> byStatus(ReferenceRateStatus status, int limit) {
        return rates.findByStatusNewestFirst(status.name(), limit);
    }

    public int maxAgeWorkingDays() {
        return properties.maxAgeWorkingDays();
    }

    private static String percent(BigDecimal fraction) {
        return fraction.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
