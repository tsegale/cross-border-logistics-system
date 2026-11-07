package com.freightflow.banking.journal;

import com.freightflow.banking.journal.JournalEntry.PostingInstruction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryUnitTest {

    @Test
    void balancedSingleAndMultiCurrencyEntriesPassDomainCheck() {
        UUID a = UUID.fromString("01930000-0000-7000-8000-000000000001");
        UUID b = UUID.fromString("01930000-0000-7000-8000-000000000002");
        UUID c = UUID.fromString("01930000-0000-7000-8000-000000000003");
        UUID d = UUID.fromString("01930000-0000-7000-8000-000000000004");

        JournalEntry.assertBalanced(List.of(
                new PostingInstruction(a, new BigDecimal("-150.00"), "NAD"),
                new PostingInstruction(b, new BigDecimal("150.00"), "NAD")
        ));

        JournalEntry.assertBalanced(List.of(
                new PostingInstruction(a, new BigDecimal("-2000.00"), "NAD"),
                new PostingInstruction(b, new BigDecimal("2000.00"), "NAD"),
                new PostingInstruction(c, new BigDecimal("-99.50"), "EUR"),
                new PostingInstruction(d, new BigDecimal("99.50"), "EUR")
        ));
    }

    @Test
    void rejectsSinglePostingZeroAmountOrUnbalancedSumPerCurrency() {
        UUID a = UUID.fromString("01930000-0000-7000-8000-000000000001");
        UUID b = UUID.fromString("01930000-0000-7000-8000-000000000002");

        assertThatThrownBy(() -> JournalEntry.assertBalanced(List.of(
                new PostingInstruction(a, new BigDecimal("100.00"), "NAD")
        ))).isInstanceOf(UnbalancedJournalEntryException.class)
                .hasMessageContaining("at least two postings");

        assertThatThrownBy(() -> JournalEntry.assertBalanced(List.of(
                new PostingInstruction(a, BigDecimal.ZERO, "NAD"),
                new PostingInstruction(b, BigDecimal.ZERO, "NAD")
        ))).isInstanceOf(UnbalancedJournalEntryException.class)
                .hasMessageContaining("non-zero");

        assertThatThrownBy(() -> JournalEntry.assertBalanced(List.of(
                new PostingInstruction(a, new BigDecimal("-100.00"), "NAD"),
                new PostingInstruction(b, new BigDecimal("99.99"), "NAD")
        ))).isInstanceOf(UnbalancedJournalEntryException.class)
                .hasMessageContaining("NAD");

        assertThatThrownBy(() -> JournalEntry.assertBalanced(List.of(
                new PostingInstruction(a, new BigDecimal("-100.00"), "NAD"),
                new PostingInstruction(b, new BigDecimal("100.00"), "EUR")
        ))).isInstanceOf(UnbalancedJournalEntryException.class);
    }

    @Test
    void statementCursorEncodesDecodesAndRejectsForeignOrMalformedTokens() {
        UUID account1 = UUID.fromString("01930000-0000-7000-8000-000000000101");
        UUID account2 = UUID.fromString("01930000-0000-7000-8000-000000000102");
        UUID postingId = UUID.fromString("01930000-0000-7000-8000-000000000201");
        Instant ts = Instant.parse("2026-11-07T18:30:00.123456Z");

        String token = StatementCursor.after(account1, ts, postingId).encode();
        StatementCursor.Decoded decoded = StatementCursor.decode(token, account1);
        assertThat(decoded.createdAt()).isEqualTo(ts);
        assertThat(decoded.postingId()).isEqualTo(postingId);

        assertThatThrownBy(() -> StatementCursor.decode(token, account2))
                .isInstanceOf(InvalidStatementCursorException.class)
                .hasMessageContaining("different account");

        assertThatThrownBy(() -> StatementCursor.decode("not-base64-json!!", account1))
                .isInstanceOf(InvalidStatementCursorException.class);
    }
}
