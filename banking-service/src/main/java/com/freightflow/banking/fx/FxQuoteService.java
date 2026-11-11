package com.freightflow.banking.fx;

import com.freightflow.banking.security.Caller;
import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.fx.ExchangeRateProvider.RateQuote;
import com.freightflow.banking.fx.dto.CreateFxQuoteRequest;
import com.freightflow.banking.fx.dto.FxQuoteResponse;
import com.freightflow.banking.journal.InvalidMonetaryAmountException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class FxQuoteService {

    private final FxQuoteRepository quotes;
    private final ExchangeRateProvider rateProvider;
    private final BankingProperties properties;
    private final Clock clock;

    public FxQuoteService(
            FxQuoteRepository quotes,
            ExchangeRateProvider rateProvider,
            BankingProperties properties,
            Clock clock) {
        this.quotes = quotes;
        this.rateProvider = rateProvider;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public FxQuoteResponse createQuote(Caller caller, CreateFxQuoteRequest request) {
        if ((request.sellAmount() == null) == (request.buyAmount() == null)) {
            throw new InvalidFxQuoteRequestException("Exactly one of sellAmount or buyAmount must be provided");
        }

        RateQuote rateQuote = rateProvider.lookupRate(request.sellCurrency(), request.buyCurrency());
        String sellCurrency = rateQuote.sellCurrency();
        String buyCurrency = rateQuote.buyCurrency();

        BigDecimal sellAmount;
        BigDecimal buyAmount;

        if (request.sellAmount() != null) {
            if (request.sellAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidMonetaryAmountException("sellAmount must be positive");
            }
            if (MoneyRounding.hasExcessMinorPrecision(request.sellAmount(), sellCurrency)) {
                throw new InvalidMonetaryAmountException(
                        "sellAmount " + request.sellAmount() + " has more than "
                                + MoneyRounding.minorUnits(sellCurrency) + " decimal places");
            }
            sellAmount = MoneyRounding.normalizeCustomerAmount(request.sellAmount(), sellCurrency);
            BigDecimal convertedBuy = MoneyRounding.roundToMinorUnits(
                    sellAmount.multiply(rateQuote.effectiveRate()), buyCurrency);
            if (convertedBuy.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidMonetaryAmountException("sellAmount converts to zero " + buyCurrency);
            }
            buyAmount = MoneyRounding.normalizeCustomerAmount(convertedBuy, buyCurrency);
        } else {
            if (request.buyAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidMonetaryAmountException("buyAmount must be positive");
            }
            if (MoneyRounding.hasExcessMinorPrecision(request.buyAmount(), buyCurrency)) {
                throw new InvalidMonetaryAmountException(
                        "buyAmount " + request.buyAmount() + " has more than "
                                + MoneyRounding.minorUnits(buyCurrency) + " decimal places");
            }
            buyAmount = MoneyRounding.normalizeCustomerAmount(request.buyAmount(), buyCurrency);
            BigDecimal rawSell = buyAmount.divide(
                    rateQuote.effectiveRate(),
                    MoneyRounding.RATE_SCALE + 4,
                    MoneyRounding.ROUNDING_MODE
            );
            BigDecimal convertedSell = MoneyRounding.roundToMinorUnits(rawSell, sellCurrency);
            if (convertedSell.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidMonetaryAmountException("buyAmount converts to zero " + sellCurrency);
            }
            sellAmount = MoneyRounding.normalizeCustomerAmount(convertedSell, sellCurrency);
        }

        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.fx().quoteTtl());

        FxQuote saved = quotes.save(FxQuote.create(
                sellCurrency,
                buyCurrency,
                sellAmount,
                buyAmount,
                rateQuote.midRate(),
                rateQuote.spread(),
                rateQuote.effectiveRate(),
                now,
                expiresAt
        ).ownedBy(caller.isCustomer() ? caller.requireOrganization() : null));
        quotes.flush();
        return FxQuoteResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public FxQuoteResponse getQuote(Caller caller, UUID id) {
        FxQuote quote = quotes.findById(id)
                .filter(found -> caller.mayAccess(found.getOwnerOrganizationId()))
                .orElseThrow(() -> new FxQuoteNotFoundException(id));
        return FxQuoteResponse.from(quote);
    }

    @Transactional
    public FxQuote consumeQuoteForTransfer(
            Caller caller,
            UUID quoteId,
            String sourceCurrency,
            String targetCurrency,
            BigDecimal requestedAmount,
            String requestedCurrency) {
        // Only the organisation that asked for a quote may use it; operators may use any (ADR 0018).
        FxQuote quote = quotes.findByIdForUpdate(quoteId)
                .filter(found -> caller.mayAccess(found.getOwnerOrganizationId()))
                .orElseThrow(() -> new FxQuoteNotFoundException(quoteId));

        if (!quote.getSellCurrency().equals(sourceCurrency) || !quote.getBuyCurrency().equals(targetCurrency)) {
            throw new FxQuoteUnavailableException(
                    quoteId,
                    "fx-quote-mismatch",
                    "FX quote mismatch",
                    "FX quote " + quoteId + " is for " + quote.getSellCurrency() + "->" + quote.getBuyCurrency()
                            + ", not " + sourceCurrency + "->" + targetCurrency
            );
        }

        BigDecimal normalizedRequest = MoneyRounding.normalizeCustomerAmount(requestedAmount, requestedCurrency);
        boolean matchesSell = requestedCurrency.equals(quote.getSellCurrency())
                && normalizedRequest.compareTo(quote.getSellAmount()) == 0;
        boolean matchesBuy = requestedCurrency.equals(quote.getBuyCurrency())
                && normalizedRequest.compareTo(quote.getBuyAmount()) == 0;

        if (!matchesSell && !matchesBuy) {
            throw new FxQuoteUnavailableException(
                    quoteId,
                    "fx-quote-mismatch",
                    "FX quote mismatch",
                    "Transfer amount " + requestedAmount.stripTrailingZeros().toPlainString() + " " + requestedCurrency
                            + " does not match FX quote " + quoteId
            );
        }

        quote.consume(clock.instant());
        quotes.saveAndFlush(quote);
        return quote;
    }
}
