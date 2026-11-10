package com.freightflow.banking.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountNumberTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "FF87NAD000000001",
            "FF60NAD000000002",
            "FF33NAD000000003",
            "FF29ZAR000000001",
            "FF02ZAR000000002",
            "FF72ZAR000000003",
            "FF14EUR000000001",
            "FF84EUR000000002",
            "FF57EUR000000003"
    })
    void seededSystemAccountNumbersPassIso7064Mod97Validation(String accountNumber) {
        assertThat(AccountNumber.isValid(accountNumber)).isTrue();
        assertThat(AccountNumber.validate("  " + accountNumber.toLowerCase(Locale.ROOT) + " ")).isEqualTo(accountNumber);
    }

    @Test
    void generatedAccountNumbersValidateAndMatchSeededSystemNumbers() {
        assertThat(AccountNumber.generate("NAD", 1)).isEqualTo("FF87NAD000000001");
        assertThat(AccountNumber.generate("NAD", 2)).isEqualTo("FF60NAD000000002");
        assertThat(AccountNumber.generate("NAD", 3)).isEqualTo("FF33NAD000000003");
        assertThat(AccountNumber.generate("ZAR", 1)).isEqualTo("FF29ZAR000000001");
        assertThat(AccountNumber.generate("ZAR", 2)).isEqualTo("FF02ZAR000000002");
        assertThat(AccountNumber.generate("ZAR", 3)).isEqualTo("FF72ZAR000000003");
        assertThat(AccountNumber.generate("EUR", 1)).isEqualTo("FF14EUR000000001");
        assertThat(AccountNumber.generate("EUR", 2)).isEqualTo("FF84EUR000000002");
        assertThat(AccountNumber.generate("EUR", 3)).isEqualTo("FF57EUR000000003");
    }

    @Test
    void catchesSingleDigitTyposAndAdjacentTranspositions() {
        String valid = AccountNumber.generate("NAD", 1042);
        assertThat(AccountNumber.isValid(valid)).isTrue();

        // Single digit error in sequence
        String singleDigitTypo = valid.substring(0, 15) + (valid.charAt(15) == '9' ? '8' : '9');
        assertThat(AccountNumber.isValid(singleDigitTypo)).isFalse();
        assertThatThrownBy(() -> AccountNumber.validate(singleDigitTypo))
                .isInstanceOf(InvalidAccountNumberException.class)
                .hasMessageContaining("MOD 97-10");

        // Adjacent transposition in sequence ("1042" -> "1402")
        String transposed = valid.substring(0, 12) + "1402";
        assertThat(AccountNumber.isValid(transposed)).isFalse();

        // Corrupted check digits
        String corruptedCheck = "FF00" + valid.substring(4);
        assertThat(AccountNumber.isValid(corruptedCheck)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "DE89370400440532013000", "FF87NAD123", "FF99NAD000000001"})
    void rejectsMalformedOrOutOfRangeStrings(String invalid) {
        assertThat(AccountNumber.isValid(invalid)).isFalse();
        assertThatThrownBy(() -> AccountNumber.validate(invalid))
                .isInstanceOf(InvalidAccountNumberException.class);
    }

    @Test
    void rejectsNullAndInvalidGeneratorInputs() {
        assertThat(AccountNumber.isValid(null)).isFalse();
        assertThatThrownBy(() -> AccountNumber.generate("NA", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountNumber.generate("NAD", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountNumber.generate("NAD", 1_000_000_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountDomainAppliesPostingsAndPreventsNegativeCustomerBalance() {
        java.time.Instant t0 = java.time.Instant.parse("2026-11-07T18:00:00Z");
        java.time.Instant t1 = java.time.Instant.parse("2026-11-07T18:01:00Z");
        Account customer = Account.createCustomer("FF87NAD000000001", "CUST-1", "NAD", t0);

        assertThat(customer.getAccountType().isCustomer()).isTrue();
        assertThat(customer.getAccountType().isSystem()).isFalse();
        assertThat(AccountType.CLEARING.isSystem()).isTrue();
        assertThat(AccountType.CLEARING.isCustomer()).isFalse();

        assertThat(customer.applyPosting(new java.math.BigDecimal("100.00"), t1))
                .isEqualTo(new java.math.BigDecimal("100.0000"));
        assertThat(customer.applyPosting(new java.math.BigDecimal("-40.00"), t1))
                .isEqualTo(new java.math.BigDecimal("60.0000"));

        assertThatThrownBy(() -> customer.applyPosting(new java.math.BigDecimal("-60.01"), t1))
                .isInstanceOf(InsufficientFundsException.class)
                .satisfies(ex -> {
                    InsufficientFundsException ife = (InsufficientFundsException) ex;
                    assertThat(ife.getAccountNumber()).isEqualTo("FF87NAD000000001");
                    assertThat(ife.getCurrency()).isEqualTo("NAD");
                    assertThat(ife.getAvailableBalance()).isEqualByComparingTo("60.00");
                    assertThat(ife.getRequestedAmount()).isEqualByComparingTo("60.01");
                });
    }
}
