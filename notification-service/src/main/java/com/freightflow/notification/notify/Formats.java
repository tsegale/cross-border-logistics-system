package com.freightflow.notification.notify;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Currency;
import java.util.Locale;

/**
 * Amounts and dates as the reader's locale writes them (ADR 0027): de-DE {@code 1.840,50 €} and {@code 20.11.2026},
 * en-GB {@code €1,840.50} and {@code 20 Nov 2026}. Money is rounded to the currency's minor units, half even, the way
 * the ledger rounds (CLAUDE.md). An instant is written as the calendar date in the organisation's time zone
 * (its settings): 23:30 UTC on 24 November is the 25th in Windhoek and in Berlin.
 */
public final class Formats {

    private Formats() {
    }

    public static String money(BigDecimal amount, String currencyCode, Locale locale) {
        Currency currency = Currency.getInstance(currencyCode);
        NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(currency);
        format.setMinimumFractionDigits(currency.getDefaultFractionDigits());
        format.setMaximumFractionDigits(currency.getDefaultFractionDigits());
        format.setRoundingMode(RoundingMode.HALF_EVEN);
        return format.format(amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_EVEN));
    }

    public static String date(LocalDate date, Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date);
    }

    public static String date(Instant instant, Locale locale, ZoneId zone) {
        return date(LocalDate.ofInstant(instant, zone), locale);
    }
}
