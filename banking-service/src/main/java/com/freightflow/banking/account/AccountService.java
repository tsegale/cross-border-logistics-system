package com.freightflow.banking.account;

import com.freightflow.banking.security.Caller;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.config.BankingProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final BankingProperties properties;
    private final SystemAccountBalances systemBalances;
    private final Clock clock;

    public AccountService(AccountRepository accounts, BankingProperties properties,
                          SystemAccountBalances systemBalances, Clock clock) {
        this.accounts = accounts;
        this.properties = properties;
        this.systemBalances = systemBalances;
        this.clock = clock;
    }

    /**
     * A customer opens an account for its own organisation; an operator opens one for the organisation it names
     * (ADR 0018). A customer naming another organisation is refused, not silently corrected.
     */
    @Transactional
    public AccountResponse createCustomerAccount(Caller caller, CreateAccountRequest request) {
        UUID owner;
        if (caller.isCustomer()) {
            owner = caller.requireOrganization();
            if (request.organizationId() != null && !request.organizationId().equals(owner)) {
                throw new AccessDeniedException("A customer opens accounts for its own organisation only");
            }
        } else if (caller.isOperator()) {
            if (request.organizationId() == null) {
                throw new IllegalArgumentException("organizationId is required when an operator opens an account");
            }
            owner = request.organizationId();
        } else {
            throw new AccessDeniedException("Only customers and operators open accounts");
        }
        Currency currency = MoneyRounding.validateSupportedCurrency(
                request.currency(), properties.normalizedCurrencies());
        long sequence = accounts.nextAccountSequence();
        String accountNumber = AccountNumber.generate(currency.getCurrencyCode(), sequence);
        Instant now = clock.instant();
        Account saved = accounts.save(Account.createCustomer(accountNumber, owner, currency.getCurrencyCode(), now));
        return AccountResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(Caller caller, String idOrAccountNumber) {
        Account account = resolveVisible(caller, idOrAccountNumber);
        return AccountResponse.from(account, systemBalances.balanceOf(account));
    }

    /**
     * The account, if this caller may see it (ADR 0018): customers their own organisation's accounts, operators and
     * auditors every account. Anything else is the same 404 as an account that does not exist, so ids cannot be
     * probed.
     */
    @Transactional(readOnly = true)
    public Account resolveVisible(Caller caller, String idOrAccountNumber) {
        Account account = resolveAccount(idOrAccountNumber);
        if (!caller.mayAccess(account.getOwnerOrganizationId())) {
            throw new AccountNotFoundException(idOrAccountNumber.trim());
        }
        return account;
    }

    /**
     * Resolves an account either by UUIDv7 {@code id} or by check-digit {@code accountNumber}.
     * When {@code idOrAccountNumber} is not a UUID, it is validated against the ISO 7064 MOD 97-10
     * check-digit scheme first so mistyped account numbers fail with 400 {@code invalid-account-number}
     * rather than 404 {@code account-not-found}.
     */
    @Transactional(readOnly = true)
    public Account resolveAccount(String idOrAccountNumber) {
        if (idOrAccountNumber == null || idOrAccountNumber.isBlank()) {
            throw new InvalidAccountNumberException(idOrAccountNumber, "Account identifier must not be blank");
        }
        String trimmed = idOrAccountNumber.trim();
        UUID uuid = tryParseUuid(trimmed);
        if (uuid != null) {
            return accounts.findById(uuid)
                    .orElseThrow(() -> new AccountNotFoundException(uuid));
        }
        String validatedNumber = AccountNumber.validate(trimmed);
        return accounts.findByAccountNumber(validatedNumber)
                .orElseThrow(() -> new AccountNotFoundException(validatedNumber));
    }

    private static UUID tryParseUuid(String candidate) {
        if (candidate.length() != 36 || candidate.charAt(8) != '-') {
            return null;
        }
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
