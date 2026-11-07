package com.freightflow.logistics.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;

public final class MoneyRounding {

    public static final int STORAGE_SCALE = 4;
    public static final int RATE_SCALE = 8;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    private MoneyRounding() {
    }

    public static Currency validateSupportedCurrency(String code, Set<String> supportedCurrencies) {
        if (code == null || code.isBlank()) {
            throw new UnsupportedCurrencyException(code, supportedCurrencies);
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!supportedCurrencies.contains(normalized)) {
            throw new UnsupportedCurrencyException(normalized, supportedCurrencies);
        }
        return Currency.getInstance(normalized);
    }

    public static int minorUnits(String currencyCode) {
        return Math.max(Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT)).getDefaultFractionDigits(), 0);
    }

    public static BigDecimal roundToMinorUnits(BigDecimal amount, String currencyCode) {
        return amount.setScale(minorUnits(currencyCode), ROUNDING_MODE);
    }

    public static BigDecimal toStorageScale(BigDecimal amount) {
        return amount.setScale(STORAGE_SCALE, ROUNDING_MODE);
    }

    public static BigDecimal normalizeCustomerAmount(BigDecimal amount, String currencyCode) {
        return toStorageScale(roundToMinorUnits(amount, currencyCode));
    }

    public static boolean hasExcessMinorPrecision(BigDecimal amount, String currencyCode) {
        int allowedScale = minorUnits(currencyCode);
        return amount.stripTrailingZeros().scale() > allowedScale;
    }
}
