package com.freightflow.logistics.quote;

import com.freightflow.logistics.customs.CustomsEstimator;
import com.freightflow.logistics.customs.CustomsQuoting;
import com.freightflow.logistics.customs.CustomsTariffs;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import com.freightflow.logistics.security.Caller;
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
    private final CustomsQuoting customs;
    private final Clock clock;

    public QuoteService(
            LocationRepository locations,
            TariffRepository tariffs,
            FreightQuoteRepository quotes,
            QuotePackageRepository quotePackages,
            LogisticsProperties properties,
            IndicativePricing indicativePricing,
            CustomsQuoting customs,
            Clock clock) {
        this.customs = customs;
        this.locations = locations;
        this.tariffs = tariffs;
        this.quotes = quotes;
        this.quotePackages = quotePackages;
        this.properties = properties;
        this.indicativePricing = indicativePricing;
        this.clock = clock;
    }

    @Transactional
    public QuoteResponse createQuote(Caller caller, CreateQuoteRequest request) {
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

        // Duties and import VAT for the declared goods (ADR 0035). Customs counts the whole move's freight, whoever
        // pays it, and the goods are expected at customs after the longest transit.
        CustomsQuoting.Quoted customsEstimate = null;
        if (request.goods() != null && !request.goods().isEmpty()) {
            LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            FreightPricing.Charges wholeMove = FreightPricing.price(FreightPricing.TariffRates.of(tariff),
                    calc.chargeableUnits(), EnumSet.allOf(ChargeLeg.class));
            customsEstimate = customs.estimate(destination.getCountryCode(), request.goods(), wholeMove,
                    quoteCurrency, today, today.plusDays(tariff.getTransitDaysMax()));
        }

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
        ).ownedBy(caller.isCustomer() ? caller.requireOrganization() : null).withIndicative(shownCurrency, indicative)
                .withCustoms(customsEstimate == null ? null : customsSummary(customsEstimate, incoterm, payerRole,
                        quoteCurrency)));
        if (customsEstimate != null) {
            quotes.flush();
            customs.save(savedQuote.getId(), customsEstimate.estimate());
        }

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

        return QuoteResponse.from(savedQuote, savedPackages, customsView(savedQuote));
    }

    @Transactional(readOnly = true)
    public QuoteResponse getQuote(Caller caller, UUID id) {
        FreightQuote quote = quotes.findById(id)
                .filter(found -> caller.mayAccess(found.getOwnerOrganizationId()))
                .orElseThrow(() -> new QuoteNotFoundException(id));
        List<QuotePackage> pkgs = quotePackages.findByQuoteIdOrderByLineNumberAsc(id);
        return QuoteResponse.from(quote, pkgs, customsView(quote));
    }

    /**
     * Collected when the quoting party owes them. In the freight currency they go into the freight hold, one capture
     * split into revenue and disbursement (ADR 0036); in another into a hold of their own (ADR 0038).
     */
    private static FreightQuote.CustomsSummary customsSummary(CustomsQuoting.Quoted quoted, Incoterm incoterm,
                                                              PayerRole payerRole, String quoteCurrency) {
        CustomsEstimator.Estimate estimate = quoted.estimate();
        PayerRole dutiesPaidBy = incoterm.importDutiesPaidBy();
        boolean collected = dutiesPaidBy == payerRole;
        return new FreightQuote.CustomsSummary(estimate.market().name(), quoted.source().id(), estimate.currency(),
                estimate.duties(), estimate.importVat(), dutiesPaidBy, collected);
    }

    private QuoteResponse.CustomsDto customsView(FreightQuote quote) {
        if (quote.getCustomsMarket() == null) {
            return null;
        }
        CustomsTariffs.Source source = customs.source(quote.getCustomsTariffSourceId())
                .orElseThrow(() -> new IllegalStateException("Tariff source of quote " + quote.getId() + " vanished"));
        return QuoteResponse.CustomsDto.of(quote, source, customs.lines(quote.getId()));
    }
}
