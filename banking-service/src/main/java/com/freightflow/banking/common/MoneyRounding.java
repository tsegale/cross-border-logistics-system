package com.freightflow.banking.common;

import java.util.Locale;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Set;

/**
 * Money rounding and scale normalization rules (ADR 0007).
 *
 * <p>Database storage uses {@code NUMERIC(19, 4)} (scale 4). Customer-facing monetary amounts are
 * rounded to the ISO 4217 currency's minor units ({@link Currency#getDefaultFractionDigits()}, 2 for
 * NAD, ZAR and EUR) using {@link RoundingMode#HALF_EVEN} (banker's rounding).
 */
public final class MoneyRounding {

    public static final int STORAGE_SCALE = 4;
    public static final int RATE_SCALE = 8;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    private MoneyRounding() {
    }

    public static Currency validateSupportedCurrency(String code, Set<String> supportedCodes) {
        if (code == null || code.isBlank()) {
            throw new UnsupportedCurrencyException(code, supportedCodes);
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!supportedCodes.contains(normalized)) {
            throw new UnsupportedCurrencyException(code, supportedCodes);
        }
        try {
            return Currency.getInstance(normalized);
        } catch (IllegalArgumentException ex) {
            throw new UnsupportedCurrencyException(code, supportedCodes);
        }
    }

    public static int minorUnits(String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT));
        int digits = currency.getDefaultFractionDigits();
        return Math.max(digits, 0);
    }

    /** Rounds an amount to the currency's ISO 4217 minor units using HALF_EVEN. */
    public static BigDecimal roundToMinorUnits(BigDecimal amount, String currencyCode) {
        return amount.setScale(minorUnits(currencyCode), ROUNDING_MODE);
    }

    /** Converts a minor-unit amount to the database storage scale (4 decimal places). */
    public static BigDecimal toStorageScale(BigDecimal amount) {
        return amount.setScale(STORAGE_SCALE, ROUNDING_MODE);
    }

    /** Rounds to minor units with HALF_EVEN and then scales to NUMERIC(19, 4) for persistence. */
    public static BigDecimal normalizeCustomerAmount(BigDecimal amount, String currencyCode) {
        return roundToMinorUnits(amount, currencyCode).setScale(STORAGE_SCALE, ROUNDING_MODE);
    }

    /** Rejects customer input that specifies more fractional digits than the currency's minor units. */
    public static boolean hasExcessMinorPrecision(BigDecimal amount, String currencyCode) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() > minorUnits(currencyCode);
    }
}
