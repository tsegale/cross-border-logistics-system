package com.freightflow.banking.fx;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FxQuoteRepository extends JpaRepository<FxQuote, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM FxQuote q WHERE q.id = :id")
    Optional<FxQuote> findByIdForUpdate(@Param("id") UUID id);
}
