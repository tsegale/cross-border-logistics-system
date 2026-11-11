package com.freightflow.banking.account;

import com.freightflow.banking.common.MoneyRounding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.util.Locale;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_number", length = 16, nullable = false, unique = true, updatable = false)
    private String accountNumber;

    @Column(name = "holder_reference", length = 120, nullable = false)
    private String holderReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", length = 32, nullable = false, updatable = false)
    private AccountType accountType;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private AccountStatus status;

    @Column(name = "balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal balance;

    /** Sum of this account's active holds (ADR 0011); updated under the same row lock as the balance. */
    @Column(name = "held_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal heldAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    public static Account createCustomer(String accountNumber, String holderReference, String currency, Instant now) {
        Account account = new Account();
        account.accountNumber = AccountNumber.validate(accountNumber);
        account.holderReference = Objects.requireNonNull(holderReference, "holderReference").trim();
        account.accountType = AccountType.CUSTOMER;
        account.currency = Objects.requireNonNull(currency, "currency").trim().toUpperCase(Locale.ROOT);
        account.status = AccountStatus.ACTIVE;
        account.balance = MoneyRounding.toStorageScale(BigDecimal.ZERO);
        account.heldAmount = MoneyRounding.toStorageScale(BigDecimal.ZERO);
        account.createdAt = Objects.requireNonNull(now, "now");
        account.updatedAt = now;
        return account;
    }

    public void assertActive() {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(this.accountNumber, this.status);
        }
    }

    /**
     * Applies a signed posting amount to this account's balance. A customer debit may not take the balance below
     * the amount held for active holds: debits are checked against the available balance (ADR 0011).
     *
     * @return the updated account balance (at scale 4)
     */
    public BigDecimal applyPosting(BigDecimal signedAmount, Instant now) {
        assertActive();
        BigDecimal posting = MoneyRounding.toStorageScale(signedAmount);
        BigDecimal nextBalance = this.balance.add(posting);
        if (this.accountType.isCustomer() && posting.signum() < 0 && nextBalance.compareTo(this.heldAmount) < 0) {
            throw insufficientFunds(posting.abs());
        }
        this.balance = nextBalance;
        this.updatedAt = Objects.requireNonNull(now, "now");
        return this.balance;
    }

    /** Ledger balance minus active holds: what a debit or a new hold may use. */
    public BigDecimal getAvailableBalance() {
        return this.balance.subtract(this.heldAmount);
    }

    /** Reserves {@code amount} for a new hold; the ledger balance does not change. Caller holds the row lock. */
    public void reserve(BigDecimal amount, Instant now) {
        assertActive();
        if (!this.accountType.isCustomer()) {
            throw new IllegalStateException("Only customer accounts can be held: " + this.accountNumber);
        }
        BigDecimal reserve = MoneyRounding.toStorageScale(amount);
        if (getAvailableBalance().compareTo(reserve) < 0) {
            throw insufficientFunds(reserve);
        }
        this.heldAmount = this.heldAmount.add(reserve);
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /** Frees a reservation made by {@link #reserve}. Caller holds the row lock. */
    public void releaseReservation(BigDecimal amount, Instant now) {
        BigDecimal release = MoneyRounding.toStorageScale(amount);
        if (this.heldAmount.compareTo(release) < 0) {
            throw new IllegalStateException("Account " + this.accountNumber + " holds " + this.heldAmount
                    + ", cannot release " + release);
        }
        this.heldAmount = this.heldAmount.subtract(release);
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private InsufficientFundsException insufficientFunds(BigDecimal requested) {
        return new InsufficientFundsException(this.accountNumber, this.currency,
                MoneyRounding.roundToMinorUnits(getAvailableBalance(), this.currency),
                MoneyRounding.roundToMinorUnits(requested, this.currency));
    }

    public BigDecimal getHeldAmount() {
        return heldAmount;
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getHolderReference() {
        return holderReference;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
