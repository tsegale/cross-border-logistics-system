package com.freightflow.banking.fx.rates;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferenceRateRepository extends JpaRepository<ReferenceRate, UUID> {

    /** The rate that prices: the newest confirmed one of the pair. */
    @Query(value = """
            SELECT * FROM reference_rates
            WHERE source = :source AND base_currency = :base AND quote_currency = :quote AND status = 'CONFIRMED'
            ORDER BY value_date DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ReferenceRate> findLatestConfirmed(@Param("source") String source, @Param("base") String base,
                                                @Param("quote") String quote);

    /** The confirmed rate a new one's day-on-day move is measured against. */
    @Query(value = """
            SELECT * FROM reference_rates
            WHERE source = :source AND base_currency = :base AND quote_currency = :quote AND status = 'CONFIRMED'
              AND value_date < :before
            ORDER BY value_date DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<ReferenceRate> findConfirmedBefore(@Param("source") String source, @Param("base") String base,
                                                @Param("quote") String quote, @Param("before") LocalDate before);

    /** The newest value date stored in any status: a refresh only asks for what comes after it. */
    @Query(value = """
            SELECT max(value_date) FROM reference_rates
            WHERE source = :source AND base_currency = :base AND quote_currency = :quote
            """, nativeQuery = true)
    Optional<LocalDate> findNewestValueDate(@Param("source") String source, @Param("base") String base,
                                            @Param("quote") String quote);

    @Query(value = "SELECT * FROM reference_rates WHERE status = :status ORDER BY value_date DESC, id LIMIT :limit",
            nativeQuery = true)
    List<ReferenceRate> findByStatusNewestFirst(@Param("status") String status, @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReferenceRate r WHERE r.id = :id")
    Optional<ReferenceRate> findByIdForUpdate(@Param("id") UUID id);
}
