package com.freightflow.banking.hold;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    /** Active holds past their expiry, oldest first. Read without a lock: expiring one re-checks under the locks. */
    @Query(value = """
            SELECT id FROM holds
            WHERE status = 'ACTIVE' AND expires_at <= :now
            ORDER BY expires_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findExpiredActiveIds(@Param("now") Instant now, @Param("limit") int limit);
}
