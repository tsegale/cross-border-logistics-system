package com.freightflow.logistics.shipment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentStateMachineUnitTest {

    @ParameterizedTest
    @CsvSource({
            "AWAITING_PAYMENT, BOOKED",
            "AWAITING_PAYMENT, CANCELLED",
            "BOOKED, PICKED_UP",
            "BOOKED, CANCELLED",
            "PICKED_UP, AT_ORIGIN_PORT",
            "AT_ORIGIN_PORT, IN_TRANSIT",
            "IN_TRANSIT, AT_DESTINATION_PORT",
            "AT_DESTINATION_PORT, CUSTOMS_HOLD",
            "AT_DESTINATION_PORT, CUSTOMS_CLEARED",
            "CUSTOMS_HOLD, CUSTOMS_CLEARED",
            "CUSTOMS_CLEARED, CUSTOMS_HOLD",
            "CUSTOMS_CLEARED, OUT_FOR_DELIVERY",
            "OUT_FOR_DELIVERY, DELIVERED",
            "IN_TRANSIT, EXCEPTION",
            "EXCEPTION, IN_TRANSIT"
    })
    void allowsExpectedStateTransitions(ShipmentStatus from, ShipmentStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
        from.assertCanTransitionTo(to);
    }

    @ParameterizedTest
    @CsvSource({
            "AWAITING_PAYMENT, IN_TRANSIT",
            "AWAITING_PAYMENT, DELIVERED",
            "BOOKED, IN_TRANSIT",
            "PICKED_UP, CANCELLED",
            "AT_ORIGIN_PORT, CANCELLED",
            "IN_TRANSIT, CANCELLED",
            "AT_DESTINATION_PORT, CANCELLED",
            "CUSTOMS_HOLD, CANCELLED",
            "CUSTOMS_CLEARED, CANCELLED",
            "OUT_FOR_DELIVERY, CANCELLED",
            "DELIVERED, CANCELLED",
            "DELIVERED, IN_TRANSIT",
            "CANCELLED, BOOKED"
    })
    void rejectsIllegalStateTransitions(ShipmentStatus from, ShipmentStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
        assertThatThrownBy(() -> from.assertCanTransitionTo(to))
                .isInstanceOf(InvalidShipmentStateTransitionException.class)
                .satisfies(ex -> {
                    InvalidShipmentStateTransitionException ise = (InvalidShipmentStateTransitionException) ex;
                    assertThat(ise.getCurrentStatus()).isEqualTo(from);
                    assertThat(ise.getRequestedStatus()).isEqualTo(to);
                });
    }

    @Test
    void trackingNumberDetectsSingleSubstitutionAndTransposition() {
        AtomicLong seq = new AtomicLong(100_000L);
        String tn = TrackingNumber.generate(seq::incrementAndGet);
        assertThat(tn).startsWith("FF-").hasSize(13);
        assertThat(TrackingNumber.isValid(tn)).isTrue();
        assertThat(TrackingNumber.validate(tn.toLowerCase(Locale.ROOT))).isEqualTo(tn);

        // Single-character substitution in body (index 5 is inside the 8-char body at indices 3..10)
        char original = tn.charAt(5);
        char replacement = original == 'A' ? 'B' : 'A';
        String substituted = tn.substring(0, 5) + replacement + tn.substring(6);
        assertThat(TrackingNumber.isValid(substituted)).isFalse();
        assertThatThrownBy(() -> TrackingNumber.validate(substituted))
                .isInstanceOf(InvalidTrackingNumberException.class);

        // Adjacent transposition of two distinct characters inside the 8-char body (indices 3..9)
        AtomicLong distinctSeq = new AtomicLong(123_456_789L);
        String tnDistinct = TrackingNumber.generate(distinctSeq::getAndIncrement);
        int swapIdx = -1;
        for (int i = 3; i < 10; i++) {
            if (tnDistinct.charAt(i) != tnDistinct.charAt(i + 1)) {
                swapIdx = i;
                break;
            }
        }
        assertThat(swapIdx).isBetween(3, 9);
        String transposed = tnDistinct.substring(0, swapIdx)
                + tnDistinct.charAt(swapIdx + 1)
                + tnDistinct.charAt(swapIdx)
                + tnDistinct.substring(swapIdx + 2);
        assertThat(TrackingNumber.isValid(transposed)).isFalse();

        assertThat(TrackingNumber.isValid(null)).isFalse();
        assertThat(TrackingNumber.isValid("FF-123")).isFalse();
        assertThatThrownBy(() -> TrackingNumber.validate(null))
                .isInstanceOf(InvalidTrackingNumberException.class);
        assertThatThrownBy(() -> TrackingNumber.validate("XX-00000000-0"))
                .isInstanceOf(InvalidTrackingNumberException.class);
        assertThatThrownBy(() -> TrackingNumber.validate("FF-0000000U-0"))
                .isInstanceOf(InvalidTrackingNumberException.class);
        assertThatThrownBy(() -> TrackingNumber.generate(() -> -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountNumberValidatorChecksFormatAndIso7064CheckDigits() {
        String validAccount = buildValidAccountNumber("EUR", "000000001");
        assertThat(AccountNumberValidator.isValid(validAccount)).isTrue();
        assertThat(AccountNumberValidator.validate(" " + validAccount.toLowerCase(Locale.ROOT) + " ")).isEqualTo(validAccount);

        String badCheck = "FF" + (validAccount.substring(2, 4).equals("00") ? "01" : "00") + validAccount.substring(4);
        assertThat(AccountNumberValidator.isValid(badCheck)).isFalse();
        assertThatThrownBy(() -> AccountNumberValidator.validate(badCheck))
                .isInstanceOf(InvalidAccountNumberException.class);

        assertThat(AccountNumberValidator.isValid(null)).isFalse();
        assertThat(AccountNumberValidator.isValid("FF123")).isFalse();
        assertThatThrownBy(() -> AccountNumberValidator.validate(""))
                .isInstanceOf(InvalidAccountNumberException.class);
        assertThatThrownBy(() -> AccountNumberValidator.validate("XX00EUR000000001"))
                .isInstanceOf(InvalidAccountNumberException.class);
    }

    @Test
    void etagsFormattingAndValidation() {
        assertThat(ETags.format(0L)).isEqualTo("\"0\"");
        ETags.assertMatches(null, 0L);
        ETags.assertMatches("   ", 0L);
        ETags.assertMatches("*", 0L);
        ETags.assertMatches("\"0\"", 0L);
        ETags.assertMatches("W/\"0\"", 0L);
        ETags.assertMatches("0", 0L);

        assertThatThrownBy(() -> ETags.assertMatches("\"1\"", 0L))
                .isInstanceOf(PreconditionFailedException.class)
                .satisfies(ex -> {
                    PreconditionFailedException pfe = (PreconditionFailedException) ex;
                    assertThat(pfe.getExpectedETag()).isEqualTo("\"1\"");
                    assertThat(pfe.getCurrentETag()).isEqualTo("\"0\"");
                });

        assertThatThrownBy(() -> ETags.assertMatches("\"not-a-number\"", 0L))
                .isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void shipmentCursorRoundTripsAndRejectsTampering() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-19T12:00:00Z");
        ShipmentCursor cursor = new ShipmentCursor("AWAITING_PAYMENT", now, id);
        String encoded = cursor.encode();

        ShipmentCursor decoded = ShipmentCursor.decode(encoded, "AWAITING_PAYMENT");
        assertThat(decoded).isEqualTo(cursor);

        assertThatThrownBy(() -> ShipmentCursor.decode(encoded, "BOOKED"))
                .isInstanceOf(InvalidShipmentCursorException.class);
        assertThatThrownBy(() -> ShipmentCursor.decode("   ", null))
                .isInstanceOf(InvalidShipmentCursorException.class);
        assertThatThrownBy(() -> ShipmentCursor.decode("not-valid-base64!!!", null))
                .isInstanceOf(InvalidShipmentCursorException.class);
    }

    static String buildValidAccountNumber(String currency, String nineDigits) {
        String rearranged = currency + nineDigits + "FF00";
        long remainder = 0L;
        for (int i = 0; i < rearranged.length(); i++) {
            char ch = rearranged.charAt(i);
            if (ch >= '0' && ch <= '9') {
                remainder = (remainder * 10L + (ch - '0')) % 97L;
            } else {
                int numeric = ch - 'A' + 10;
                remainder = (remainder * 100L + numeric) % 97L;
            }
        }
        int check = (int) (98L - remainder);
        return String.format("FF%02d%s%s", check, currency, nineDigits);
    }
}
