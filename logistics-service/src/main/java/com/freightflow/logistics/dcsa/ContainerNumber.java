package com.freightflow.logistics.dcsa;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An ISO 6346 container number (ADR 0037): a three-letter owner code, an equipment category (U freight container,
 * J detachable equipment, Z trailer or chassis), a six-digit serial number and a check digit. The check digit is the
 * sum of each of the first ten characters' values times 2 to the power of its position, modulo 11, with 10 counting
 * as 0. Letters have values 10 to 38, skipping multiples of 11 (A = 10, B = 12, ..., K = 21, ..., Z = 38).
 */
public final class ContainerNumber {

    private static final Pattern FORMAT = Pattern.compile("^[A-Z]{3}[UJZ][0-9]{6}[0-9]$");

    private ContainerNumber() {
    }

    /** The number normalised (upper case, no spaces or hyphens), or an exception saying what is wrong. */
    public static String validate(String raw) {
        if (raw == null) {
            throw new InvalidContainerNumberException(null, "containerNumber is required");
        }
        String number = raw.replaceAll("[\s-]", "").toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(number).matches()) {
            throw new InvalidContainerNumberException(raw, "Container number '" + raw + "' must be an owner code of "
                    + "three letters, U, J or Z, six digits and a check digit (ISO 6346)");
        }
        int expected = checkDigit(number.substring(0, 10));
        if (expected != number.charAt(10) - '0') {
            throw new InvalidContainerNumberException(raw, "Container number '" + raw + "' fails the ISO 6346 "
                    + "check digit: expected " + expected);
        }
        return number;
    }

    public static boolean isValid(String raw) {
        try {
            validate(raw);
            return true;
        } catch (InvalidContainerNumberException ex) {
            return false;
        }
    }

    static int checkDigit(String firstTen) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += value(firstTen.charAt(i)) << i;
        }
        return sum % 11 % 10;
    }

    private static int value(char ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        int value = ch - 'A' + 10;
        // 11, 22 and 33 are skipped: A = 10, B = 12, ..., K = 21, L = 23, ..., U = 32, V = 34, ..., Z = 38.
        return value + (value - 1) / 10;
    }

    public static class InvalidContainerNumberException extends RuntimeException {
        private final String containerNumber;

        public InvalidContainerNumberException(String containerNumber, String message) {
            super(message);
            this.containerNumber = containerNumber;
        }

        public String getContainerNumber() {
            return containerNumber;
        }
    }
}
