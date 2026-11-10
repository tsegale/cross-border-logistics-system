package com.freightflow.logistics.shipment;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FF-&lt;8 Crockford Base32&gt;-&lt;MOD 37-2 check&gt; (ADR 0009). */
class TrackingNumberTest {

    private static final String ALPHABET = TrackingNumber.ALPHABET;

    private static List<String> sample() {
        AtomicLong seq = new AtomicLong(1_048_576L);
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            numbers.add(TrackingNumber.generate(() -> seq.getAndAdd(7_919L)));
        }
        return numbers;
    }

    @Test
    void generatedNumbersAreValidAndUseOnlyCrockfordCharacters() {
        for (String number : sample()) {
            assertThat(number).matches("FF-[0-9A-HJ-NP-TV-Z]{8}-[0-9A-HJ-NP-TV-Z]");
            assertThat(TrackingNumber.isValid(number)).isTrue();
        }
    }

    @Test
    void everySingleCharacterSubstitutionIsDetected() {
        for (String number : sample()) {
            for (int position : bodyAndCheckPositions()) {
                for (char replacement : ALPHABET.toCharArray()) {
                    if (replacement == number.charAt(position)) {
                        continue;
                    }
                    String typo = number.substring(0, position) + replacement + number.substring(position + 1);
                    assertThat(TrackingNumber.isValid(typo)).as("%s -> %s", number, typo).isFalse();
                }
            }
        }
    }

    @Test
    void everyAdjacentTranspositionIsDetected() {
        for (String number : sample()) {
            for (int position = 3; position < 10; position++) {
                char a = number.charAt(position);
                char b = number.charAt(position + 1);
                if (a == b) {
                    continue;
                }
                String swapped = number.substring(0, position) + b + a + number.substring(position + 2);
                assertThat(TrackingNumber.isValid(swapped)).as("%s -> %s", number, swapped).isFalse();
            }
        }
    }

    @Test
    void lowercaseAndLookAlikeLettersNormalise() {
        String number = sample().getFirst();
        String typed = number.toLowerCase(java.util.Locale.ROOT).replace('1', 'l').replace('0', 'o');
        assertThat(TrackingNumber.validate(typed)).isEqualTo(number);
    }

    @Test
    void wrongShapeIsRejected() {
        assertThatThrownBy(() -> TrackingNumber.validate("FF-1234567-0")).isInstanceOf(InvalidTrackingNumberException.class);
        assertThatThrownBy(() -> TrackingNumber.validate("XX-12345678-0")).isInstanceOf(InvalidTrackingNumberException.class);
        assertThatThrownBy(() -> TrackingNumber.validate(" ")).isInstanceOf(InvalidTrackingNumberException.class);
        assertThatThrownBy(() -> TrackingNumber.validate("FF-1234U678-0")).isInstanceOf(InvalidTrackingNumberException.class);
    }

    private static int[] bodyAndCheckPositions() {
        return new int[] {3, 4, 5, 6, 7, 8, 9, 10, 12};
    }
}
