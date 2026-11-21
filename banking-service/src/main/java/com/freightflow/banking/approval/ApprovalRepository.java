package com.freightflow.banking.approval;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRepository extends JpaRepository<ApprovalRequest, UUID> {

    /** The decision's lock: two checkers deciding one request at once run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ApprovalRequest a WHERE a.id = :id")
    Optional<ApprovalRequest> findByIdForUpdate(@Param("id") UUID id);

    /** Oldest first, so the request closest to expiring is at the top of a checker's queue. */
    @Query(value = """
            SELECT * FROM approval_requests
            WHERE status = :status
            ORDER BY requested_at, id
            LIMIT :limit
            """, nativeQuery = true)
    List<ApprovalRequest> findOldestFirst(@Param("status") String status, @Param("limit") int limit);

    @Query(value = """
            SELECT id FROM approval_requests
            WHERE status = 'PENDING' AND expires_at <= :now
            ORDER BY expires_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findExpiredPendingIds(@Param("now") Instant now, @Param("limit") int limit);
}
