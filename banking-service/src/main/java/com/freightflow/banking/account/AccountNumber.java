package com.freightflow.banking.account;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * FreightFlow account numbers with ISO 7064 MOD 97-10 check digits (the IBAN check-digit scheme).
 *
 * <p>Format: {@code FF<cc><CCC><9-digit-sequence>} (16 characters total), for example
 * {@code FF87NAD000000001} or {@code FF42EUR000001000}:
 * <ul>
 *   <li>{@code FF}: 2-letter FreightFlow ledger prefix</li>
 *   <li>{@code <cc>}: 2 decimal check digits ({@code 02}..{@code 98}) computed under ISO 7064 MOD 97-10</li>
 *   <li>{@code <CCC>}: 3-letter ISO 4217 currency code ({@code NAD}, {@code ZAR}, {@code EUR})</li>
 *   <li>{@code <9-digit-sequence>}: zero-padded 9-digit sequence number</li>
 * </ul>
 *
 * <p>Validation moves the first 4 characters ({@code FF<cc>}) to the end, replaces each letter {@code A..Z}
 * with two digits {@code 10..35}, and checks that the resulting integer modulo 97 equals {@code 1}.
 */
public final class AccountNumber {

    public static final String PREFIX = "FF";
    private static final Pattern FORMAT = Pattern.compile("^FF([0-9]{2})([A-Z]{3})([0-9]{9})$");
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private AccountNumber() {
    }

    /** Generates a 16-character account number with ISO 7064 MOD 97-10 check digits. */
    public static String generate(String currencyCode, long sequenceNumber) {
        if (currencyCode == null || !currencyCode.matches("^[A-Za-z]{3}$")) {
            throw new IllegalArgumentException("Currency code must be 3 ASCII letters: " + currencyCode);
        }
        if (sequenceNumber < 1 || sequenceNumber > 999_999_999L) {
            throw new IllegalArgumentException("Sequence number must be in 1..999999999: " + sequenceNumber);
        }
        String normalizedCurrency = currencyCode.toUpperCase(Locale.ROOT);
        String bban = String.format(Locale.ROOT, "%s%09d", normalizedCurrency, sequenceNumber);
        int checkDigits = computeCheckDigits(bban);
        return String.format(Locale.ROOT, "%s%02d%s", PREFIX, checkDigits, bban);
    }

    /** Validates an account number and returns it normalized (trimmed, uppercase), or throws {@link InvalidAccountNumberException}. */
    public static String validate(String rawAccountNumber) {
        if (rawAccountNumber == null || rawAccountNumber.isBlank()) {
            throw new InvalidAccountNumberException(rawAccountNumber, "Account number must not be blank");
        }
        String candidate = rawAccountNumber.trim().toUpperCase(Locale.ROOT);
        var matcher = FORMAT.matcher(candidate);
        if (!matcher.matches()) {
            throw new InvalidAccountNumberException(rawAccountNumber,
                    "Account number '" + rawAccountNumber + "' must match format FF<2 check digits><3-letter currency><9 digits>");
        }
        int check = Integer.parseInt(matcher.group(1));
        if (check < 2 || check > 98 || mod97(candidate) != 1) {
            throw new InvalidAccountNumberException(rawAccountNumber,
                    "Account number '" + rawAccountNumber + "' failed ISO 7064 MOD 97-10 check-digit verification");
        }
        return candidate;
    }

    public static boolean isValid(String rawAccountNumber) {
        try {
            validate(rawAccountNumber);
            return true;
        } catch (InvalidAccountNumberException ex) {
            return false;
        }
    }

    static int computeCheckDigits(String bban) {
        int remainder = mod97(PREFIX + "00" + bban);
        return 98 - remainder;
    }

    static int mod97(String accountNumber) {
        String rearranged = accountNumber.substring(4) + accountNumber.substring(0, 4);
        StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
        for (int i = 0; i < rearranged.length(); i++) {
            char ch = rearranged.charAt(i);
            if (ch >= '0' && ch <= '9') {
                numeric.append(ch);
            } else {
                numeric.append(ch - 'A' + 10);
            }
        }
        return new BigInteger(numeric.toString()).mod(NINETY_SEVEN).intValue();
    }
}
