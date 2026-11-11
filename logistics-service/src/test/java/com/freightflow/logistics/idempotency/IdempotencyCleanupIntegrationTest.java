package com.freightflow.logistics.idempotency;

import com.freightflow.logistics.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyCleanupIntegrationTest extends IntegrationTest {

    @Autowired
    private IdempotencyCleanupJob cleanupJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void expiredKeysArePurgedAndLiveOnesKept() {
        jdbcTemplate.update("""
                INSERT INTO idempotency_keys (scope, caller, idempotency_key, request_hash, status, http_status, response_body,
                                              created_at, expires_at)
                VALUES ('cleanup-test', 'test', 'expired', repeat('a', 64), 'COMPLETED', 201, '{}', now() - interval '2 days',
                        now() - interval '1 day'),
                       ('cleanup-test', 'test', 'live', repeat('a', 64), 'COMPLETED', 201, '{}', now(), now() + interval '1 day')
                """);

        cleanupJob.purgeExpiredKeys();

        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_key FROM idempotency_keys WHERE scope = 'cleanup-test'", String.class))
                .containsExactly("live");
    }
}
