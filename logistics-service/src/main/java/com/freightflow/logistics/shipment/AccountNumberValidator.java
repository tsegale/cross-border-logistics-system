package com.freightflow.logistics.shipment;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates {@code payerAccountNumber} against the FreightFlow ISO 7064 MOD 97-10 format
 * ({@code FF<2 check digits><3-letter currency><9 digits>}) without calling banking-service.
 */
public final class AccountNumberValidator {

    private static final Pattern FORMAT = Pattern.compile("^FF(\\d{2})([A-Z]{3})(\\d{9})$");

    private AccountNumberValidator() {
    }

    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidAccountNumberException(raw, "payerAccountNumber must not be blank");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(normalized).matches()) {
            throw new InvalidAccountNumberException(
                    raw, "payerAccountNumber must match format FF<2 check digits><3-letter currency><9 digits>");
        }
        String rearranged = normalized.substring(4) + normalized.substring(0, 4);
        int remainder = mod97(rearranged);
        if (remainder != 1) {
            throw new InvalidAccountNumberException(
                    raw, "payerAccountNumber check digits failed ISO 7064 MOD 97-10 verification");
        }
        return normalized;
    }

    public static boolean isValid(String raw) {
        try {
            validate(raw);
            return true;
        } catch (InvalidAccountNumberException ex) {
            return false;
        }
    }

    private static int mod97(String alphanumeric) {
        long remainder = 0L;
        for (int i = 0; i < alphanumeric.length(); i++) {
            char ch = alphanumeric.charAt(i);
            if (ch >= '0' && ch <= '9') {
                remainder = (remainder * 10L + (ch - '0')) % 97L;
            } else {
                int numeric = ch - 'A' + 10;
                remainder = (remainder * 100L + numeric) % 97L;
            }
        }
        return (int) remainder;
    }
}
