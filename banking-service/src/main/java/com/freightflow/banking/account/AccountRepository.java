package com.freightflow.banking.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Query(value = "SELECT nextval('account_number_seq')", nativeQuery = true)
    long nextAccountSequence();

    Optional<Account> findByAccountNumber(String accountNumber);

    Optional<Account> findByAccountTypeAndCurrency(AccountType accountType, String currency);

    /**
     * Locks every requested account row with {@code SELECT ... FOR UPDATE} in ascending {@code id}
     * order so concurrent transfers touching the same pair in opposite directions cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
    List<Account> findAllByIdInForUpdate(@Param("ids") Collection<UUID> ids);
}
