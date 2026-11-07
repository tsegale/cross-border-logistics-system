package com.freightflow.logistics.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, IdempotencyKeyId> {
}
