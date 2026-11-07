package com.freightflow.logistics.shipment;

import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * FreightFlow tracking number in Crockford Base32 with an ISO 7064 MOD 37-2 check character (ADR 0009):
 * <pre>
 *   FF-&lt;8 Crockford Base32 chars&gt;-&lt;1 check char&gt;   (e.g. FF-00100001-K)
 * </pre>
 *
 * <p>Crockford's Base32 alphabet ({@code 0123456789ABCDEFGHJKMNPQRSTVWXYZ}) omits {@code I}, {@code L},
 * {@code O}, and {@code U} so numbers read over the phone or radio on the Walvis Bay to Hamburg corridor
 * are unambiguous; when validating user input, {@code i/I/l/L} map to {@code 1} and {@code o/O} map to {@code 0}.
 *
 * <p>Check calculation uses ISO/IEC 7064 MOD 37-2 polynomial weights \(2^{8-i} \bmod 37\) across the 8 body
 * characters so 100% of single-character substitutions and 100% of adjacent transpositions are detected.
 * During sequence generation, sequence values whose mod-37 check index is \(\ge 32\) are skipped so every
 * issued tracking number uses only the 32 URL-safe alphanumeric Crockford characters.
 */
public final class TrackingNumber {

    public static final String PREFIX = "FF-";
    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final int BODY_LENGTH = 8;
    private static final int MODULUS = 37;
    private static final long MAX_SEQUENCE = (1L << 40) - 1;

    private TrackingNumber() {
    }

    public static String generate(LongSupplier sequenceSupplier) {
        while (true) {
            long seq = sequenceSupplier.getAsLong();
            if (seq < 0 || seq > MAX_SEQUENCE) {
                throw new IllegalArgumentException("Sequence out of 40-bit Crockford Base32 range: " + seq);
            }
            char[] body = encodeBody(seq);
            int checkIndex = computeMod37CheckIndex(body);
            if (checkIndex < ALPHABET.length()) {
                return PREFIX + new String(body) + "-" + ALPHABET.charAt(checkIndex);
            }
        }
    }

    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidTrackingNumberException(raw, "Tracking number must not be blank");
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if (trimmed.length() != 13 || !trimmed.startsWith(PREFIX) || trimmed.charAt(11) != '-') {
            throw new InvalidTrackingNumberException(
                    raw, "Tracking number must match format FF-<8 Crockford Base32 chars>-<check>");
        }

        char[] body = new char[BODY_LENGTH];
        for (int i = 0; i < BODY_LENGTH; i++) {
            int val = decodeCrockfordChar(trimmed.charAt(3 + i), raw);
            body[i] = ALPHABET.charAt(val);
        }
        int actualCheckVal = decodeCrockfordChar(trimmed.charAt(12), raw);
        int expectedCheckVal = computeMod37CheckIndex(body);
        if (actualCheckVal != expectedCheckVal) {
            throw new InvalidTrackingNumberException(raw, "Tracking number check character failed verification");
        }
        return PREFIX + new String(body) + "-" + ALPHABET.charAt(actualCheckVal);
    }

    public static boolean isValid(String raw) {
        try {
            validate(raw);
            return true;
        } catch (InvalidTrackingNumberException ex) {
            return false;
        }
    }

    static char[] encodeBody(long sequence) {
        char[] chars = new char[BODY_LENGTH];
        long value = sequence;
        for (int i = BODY_LENGTH - 1; i >= 0; i--) {
            chars[i] = ALPHABET.charAt((int) (value & 31L));
            value >>>= 5;
        }
        return chars;
    }

    /**
     * ISO/IEC 7064 MOD 37-2 Pure system check calculation over the 8 Crockford Base32 values.
     */
    static int computeMod37CheckIndex(char[] body) {
        int p = 0;
        for (char c : body) {
            int val = ALPHABET.indexOf(c);
            p = ((p + val) * 2) % MODULUS;
        }
        return (MODULUS + 1 - p) % MODULUS;
    }

    private static int decodeCrockfordChar(char c, String rawInput) {
        char normalized = switch (c) {
            case 'I', 'L' -> '1';
            case 'O' -> '0';
            default -> c;
        };
        int idx = ALPHABET.indexOf(normalized);
        if (idx < 0) {
            throw new InvalidTrackingNumberException(
                    rawInput, "Invalid Crockford Base32 character '" + c + "' in tracking number");
        }
        return idx;
    }
}
