package com.freightflow.banking.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, IdempotencyKeyId> {

    /**
     * Deletes a batch of expired idempotency keys using {@code FOR UPDATE SKIP LOCKED} so
     * multiple service instances running the scheduled cleanup never block each other.
     */
    @Modifying
    @Query(value = """
            DELETE FROM idempotency_keys
            WHERE (scope, idempotency_key) IN (
                SELECT scope, idempotency_key
                FROM idempotency_keys
                WHERE expires_at <= :now
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            """, nativeQuery = true)
    int deleteExpiredBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
