package com.freightflow.logistics.quote;

import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.config.LogisticsProperties;
import com.freightflow.logistics.location.Location;
import com.freightflow.logistics.location.LocationRepository;
import com.freightflow.logistics.location.UnknownLocationException;
import com.freightflow.logistics.quote.ChargeableWeightCalculator.CalculationResult;
import com.freightflow.logistics.quote.IndicativePricing.Indicative;
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
import java.util.Set;
import java.util.UUID;

@Service
public class QuoteService {

    private final LocationRepository locations;
    private final TariffRepository tariffs;
    private final FreightQuoteRepository quotes;
    private final QuotePackageRepository quotePackages;
    private final LogisticsProperties properties;
    private final IndicativePricing indicativePricing;
    private final Clock clock;

    public QuoteService(
            LocationRepository locations,
            TariffRepository tariffs,
            FreightQuoteRepository quotes,
            QuotePackageRepository quotePackages,
            LogisticsProperties properties,
            IndicativePricing indicativePricing,
            Clock clock) {
        this.locations = locations;
        this.tariffs = tariffs;
        this.quotes = quotes;
        this.quotePackages = quotePackages;
        this.properties = properties;
        this.indicativePricing = indicativePricing;
        this.clock = clock;
    }

    @Transactional
    public QuoteResponse createQuote(CreateQuoteRequest request) {
        String indicativeCurrency = request.indicativeCurrency() == null || request.indicativeCurrency().isBlank()
                ? null
                : MoneyRounding.validateSupportedCurrency(
                        request.indicativeCurrency(), properties.normalizedCurrencies()).getCurrencyCode();

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
        // Quotes are priced in the tariff currency. Logistics never converts: banking owns FX.
        String quoteCurrency = tariff.getCurrency();
        BigDecimal noConversion = BigDecimal.ONE.setScale(MoneyRounding.RATE_SCALE, MoneyRounding.ROUNDING_MODE);

        Incoterm incoterm = request.incoterm();
        PayerRole payerRole = request.payerRole();
        Set<ChargeLeg> legs = incoterm.billableLegs(payerRole);
        if (legs.isEmpty()) {
            throw new NothingToQuoteException(incoterm, payerRole);
        }
        FreightPricing.Charges charges = FreightPricing.price(
                FreightPricing.TariffRates.of(tariff), calc.chargeableUnits(), legs);
        BigDecimal totalAmount = charges.total();

        String shownCurrency = quoteCurrency.equals(indicativeCurrency) ? null : indicativeCurrency;
        Indicative indicative = shownCurrency == null
                ? null
                : indicativePricing.convert(totalAmount, quoteCurrency, shownCurrency).orElse(null);

        Instant now = clock.instant();
        Instant validUntil = now.plus(properties.quoteValidity());

        FreightQuote savedQuote = quotes.save(FreightQuote.create(
                origin.getUnlocode(),
                destination.getUnlocode(),
                request.mode(),
                incoterm,
                payerRole,
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
                noConversion,
                quoteCurrency,
                charges.baseFreight(),
                charges.fuelSurcharge(),
                charges.originHandling(),
                charges.destinationHandling(),
                charges.importClearance(),
                totalAmount,
                tariff.getTransitDaysMin(),
                tariff.getTransitDaysMax(),
                validUntil,
                now
        ).withIndicative(shownCurrency, indicative));

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
}
