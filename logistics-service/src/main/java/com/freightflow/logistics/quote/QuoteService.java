package com.freightflow.logistics.quote;

import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.config.LogisticsProperties;
import com.freightflow.logistics.location.Location;
import com.freightflow.logistics.location.LocationRepository;
import com.freightflow.logistics.location.UnknownLocationException;
import com.freightflow.logistics.quote.ChargeableWeightCalculator.CalculationResult;
import com.freightflow.logistics.quote.dto.CreateQuoteRequest;
import com.freightflow.logistics.quote.dto.PackageSpecDto;
import com.freightflow.logistics.quote.dto.QuoteResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class QuoteService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final LocationRepository locations;
    private final TariffRepository tariffs;
    private final FreightQuoteRepository quotes;
    private final QuotePackageRepository quotePackages;
    private final LogisticsProperties properties;
    private final Clock clock;

    public QuoteService(
            LocationRepository locations,
            TariffRepository tariffs,
            FreightQuoteRepository quotes,
            QuotePackageRepository quotePackages,
            LogisticsProperties properties,
            Clock clock) {
        this.locations = locations;
        this.tariffs = tariffs;
        this.quotes = quotes;
        this.quotePackages = quotePackages;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public QuoteResponse createQuote(CreateQuoteRequest request) {
        String quoteCurrency = MoneyRounding.validateSupportedCurrency(
                request.currency(), properties.normalizedCurrencies()).getCurrencyCode();

        String originCode = request.origin().trim().toUpperCase(Locale.ROOT);
        String destCode = request.destination().trim().toUpperCase(Locale.ROOT);

        Location origin = locations.findById(originCode)
                .orElseThrow(() -> new UnknownLocationException("origin", originCode));
        Location destination = locations.findById(destCode)
                .orElseThrow(() -> new UnknownLocationException("destination", destCode));

        if (origin.getUnlocode().equals(destination.getUnlocode())) {
            throw new InvalidRouteException(
                    "same-origin-destination",
                    "Invalid route",
                    origin.getUnlocode(),
                    destination.getUnlocode(),
                    request.mode().name(),
                    "origin and destination must be distinct UN/LOCODEs"
            );
        }

        Tariff tariff = tariffs.findByOriginUnlocodeAndDestinationUnlocodeAndMode(
                        origin.getUnlocode(), destination.getUnlocode(), request.mode())
                .orElseThrow(() -> new InvalidRouteException(
                        "unsupported-route",
                        "Unsupported corridor lane or mode",
                        origin.getUnlocode(),
                        destination.getUnlocode(),
                        request.mode().name(),
                        "No tariff configured for lane " + origin.getUnlocode()
                                + " -> " + destination.getUnlocode() + " via " + request.mode()
                ));

        CalculationResult calc = ChargeableWeightCalculator.calculate(request.mode(), request.packages());
        BigDecimal fxRate = lookupConversionRate(tariff.getCurrency(), quoteCurrency);

        // Compute base freight in tariff currency, then convert to requested quote currency and round with HALF_EVEN
        BigDecimal rawFreightTariffCcy = calc.chargeableUnits().multiply(tariff.getRatePerUnit())
                .max(tariff.getMinimumCharge());
        BigDecimal baseFreightCharge = convertAndRound(rawFreightTariffCcy, fxRate, quoteCurrency);

        BigDecimal rawFuelQuoteCcy = baseFreightCharge.multiply(tariff.getFuelSurchargePercent())
                .divide(ONE_HUNDRED, MoneyRounding.STORAGE_SCALE + 4, MoneyRounding.ROUNDING_MODE);
        BigDecimal fuelSurcharge = MoneyRounding.normalizeCustomerAmount(rawFuelQuoteCcy, quoteCurrency);

        Incoterm incoterm = request.incoterm();
        BigDecimal originHandlingCharge = incoterm.includesOriginHandling()
                ? convertAndRound(tariff.getOriginHandlingFee(), fxRate, quoteCurrency)
                : MoneyRounding.toStorageScale(BigDecimal.ZERO);

        BigDecimal destinationHandlingCharge = incoterm.includesDestinationHandling()
                ? convertAndRound(tariff.getDestinationHandlingFee(), fxRate, quoteCurrency)
                : MoneyRounding.toStorageScale(BigDecimal.ZERO);

        BigDecimal importCustomsDutyCharge;
        if (incoterm.includesImportCustomsAndDuties()) {
            BigDecimal cifFreightBase = baseFreightCharge.add(fuelSurcharge).add(originHandlingCharge);
            BigDecimal rawDuty = cifFreightBase.multiply(tariff.getImportDutyPercent())
                    .divide(ONE_HUNDRED, MoneyRounding.STORAGE_SCALE + 4, MoneyRounding.ROUNDING_MODE);
            importCustomsDutyCharge = MoneyRounding.normalizeCustomerAmount(rawDuty, quoteCurrency);
        } else {
            importCustomsDutyCharge = MoneyRounding.toStorageScale(BigDecimal.ZERO);
        }

        BigDecimal totalAmount = baseFreightCharge
                .add(fuelSurcharge)
                .add(originHandlingCharge)
                .add(destinationHandlingCharge)
                .add(importCustomsDutyCharge);

        Instant now = clock.instant();
        Instant validUntil = now.plus(properties.quoteValidity());

        FreightQuote savedQuote = quotes.save(FreightQuote.create(
                origin.getUnlocode(),
                destination.getUnlocode(),
                request.mode(),
                incoterm,
                calc.billingUnit(),
                calc.totalPieces(),
                calc.actualWeightKg(),
                calc.volumeCbm(),
                calc.volumetricWeightKg(),
                calc.chargeableWeightKg(),
                calc.chargeableUnits(),
                tariff.getCurrency(),
                tariff.getRatePerUnit(),
                tariff.getMinimumCharge(),
                tariff.getFuelSurchargePercent(),
                fxRate,
                quoteCurrency,
                baseFreightCharge,
                fuelSurcharge,
                originHandlingCharge,
                destinationHandlingCharge,
                importCustomsDutyCharge,
                totalAmount,
                tariff.getTransitDaysMin(),
                tariff.getTransitDaysMax(),
                validUntil,
                now
        ));

        List<QuotePackage> savedPackages = new ArrayList<>(request.packages().size());
        for (int i = 0; i < request.packages().size(); i++) {
            PackageSpecDto pkg = request.packages().get(i);
            savedPackages.add(quotePackages.save(QuotePackage.create(
                    savedQuote.getId(),
                    i + 1,
                    pkg.lengthCm(),
                    pkg.widthCm(),
                    pkg.heightCm(),
                    pkg.weightKg(),
                    pkg.quantity(),
                    pkg.description()
            )));
        }
        quotes.flush();
        quotePackages.flush();

        return QuoteResponse.from(savedQuote, savedPackages);
    }

    @Transactional(readOnly = true)
    public QuoteResponse getQuote(UUID id) {
        FreightQuote quote = quotes.findById(id)
                .orElseThrow(() -> new QuoteNotFoundException(id));
        List<QuotePackage> pkgs = quotePackages.findByQuoteIdOrderByLineNumberAsc(id);
        return QuoteResponse.from(quote, pkgs);
    }

    private BigDecimal lookupConversionRate(String fromCurrency, String toCurrency) {
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return BigDecimal.ONE.setScale(MoneyRounding.RATE_SCALE, MoneyRounding.ROUNDING_MODE);
        }
        Map<String, BigDecimal> fromMap = properties.fxRates().get(fromCurrency.toUpperCase(Locale.ROOT));
        BigDecimal rate = fromMap != null ? fromMap.get(toCurrency.toUpperCase(Locale.ROOT)) : null;
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Missing FX conversion rate for " + fromCurrency + " -> " + toCurrency);
        }
        return rate.setScale(MoneyRounding.RATE_SCALE, MoneyRounding.ROUNDING_MODE);
    }

    private static BigDecimal convertAndRound(BigDecimal amountInTariffCcy, BigDecimal fxRate, String targetCurrency) {
        BigDecimal converted = amountInTariffCcy.multiply(fxRate);
        return MoneyRounding.normalizeCustomerAmount(converted, targetCurrency);
    }
}
