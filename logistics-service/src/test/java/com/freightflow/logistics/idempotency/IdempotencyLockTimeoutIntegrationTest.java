package com.freightflow.logistics.idempotency;

import com.freightflow.logistics.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyLockTimeoutIntegrationTest extends IntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void idempotencyLockTimeoutDoesNotLeakIntoTheRestOfTheTransaction() {
        String seenInsideAction = idempotencyService.execute(
                "lock-timeout-test",
                "key-" + UUID.randomUUID(),
                Map.of("k", "v"),
                201,
                String.class,
                () -> jdbcTemplate.queryForObject("SELECT current_setting('lock_timeout')", String.class)
        ).body();

        assertThat(seenInsideAction).isEqualTo("0");
    }
}
