package com.freightflow.banking.idempotency;

import com.freightflow.banking.config.BankingProperties;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Transactional idempotency following {@code draft-ietf-httpapi-idempotency-key-header-08} (ADR 0006).
 *
 * <p>Serialises concurrent requests for the same {@code (scope, Idempotency-Key)} with a PostgreSQL
 * transaction-scoped advisory lock, checks the SHA-256 of the canonical request payload, and writes
 * the completed status and response body in the same database transaction as the ledger entry.
 */
@Service
public class IdempotencyService {

    private static final int MAX_KEY_LENGTH = 128;
    private static final JsonMapper CANONICAL_JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final IdempotencyRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final BankingProperties properties;
    private final Clock clock;

    public IdempotencyService(
            IdempotencyRepository repository,
            JdbcTemplate jdbcTemplate,
            BankingProperties properties,
            Clock clock) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    public record IdempotentResult<T>(int httpStatus, T body, boolean replayed) {
    }

    @Transactional
    public <T> IdempotentResult<T> execute(
            String scope,
            String rawKey,
            Object canonicalPayload,
            int successHttpStatus,
            Class<T> responseType,
            Supplier<T> action) {
        String key = validateKey(rawKey);
        String requestHash = sha256Hex(scope, canonicalPayload);

        acquireAdvisoryLock(scope, key);

        Instant now = clock.instant();
        IdempotencyKeyId id = new IdempotencyKeyId(scope, key);
        Optional<IdempotencyRecord> existingOpt = repository.findById(id);

        if (existingOpt.isPresent()) {
            IdempotencyRecord existing = existingOpt.get();
            if (existing.getExpiresAt().isAfter(now)) {
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw new IdempotencyPayloadMismatchException(key);
                }
                if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                    throw new IdempotencyKeyInFlightException(key);
                }
                T replayedBody = CANONICAL_JSON.readValue(existing.getResponseBody(), responseType);
                return new IdempotentResult<>(existing.getHttpStatus(), replayedBody, true);
            }
            repository.delete(existing);
            repository.flush();
        }

        T result = action.get();
        String responseJson = CANONICAL_JSON.writeValueAsString(result);
        Instant expiresAt = now.plus(properties.idempotency().ttl());
        repository.save(IdempotencyRecord.completed(
                scope, key, requestHash, successHttpStatus, responseJson, now, expiresAt));
        return new IdempotentResult<>(successHttpStatus, result, false);
    }

    /**
     * Marks a key as {@code IN_PROGRESS} in a new transaction. Used when an operation spans phases
     * or in tests verifying that an in-flight key returns 409 Conflict with {@code Retry-After}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markInProgress(String scope, String rawKey, Object canonicalPayload) {
        String key = validateKey(rawKey);
        String requestHash = sha256Hex(scope, canonicalPayload);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.idempotency().ttl());
        repository.saveAndFlush(IdempotencyRecord.inProgress(scope, key, requestHash, now, expiresAt));
    }

    public static String validateKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required");
        }
        String trimmed = rawKey.trim();
        if (trimmed.length() > MAX_KEY_LENGTH) {
            throw new MissingIdempotencyKeyException(
                    "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return trimmed;
    }

    public static String sha256Hex(String scope, Object canonicalPayload) {
        byte[] jsonBytes = CANONICAL_JSON.writeValueAsBytes(canonicalPayload);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(scope.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(jsonBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void acquireAdvisoryLock(String scope, String key) {
        long timeoutMs = Math.max(properties.idempotency().lockTimeout().toMillis(), 100L);
        try {
            jdbcTemplate.execute("SET LOCAL lock_timeout = '" + timeoutMs + "ms'");
            jdbcTemplate.queryForObject(
                    "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))",
                    Object.class,
                    scope,
                    key
            );
        } catch (PessimisticLockingFailureException ex) {
            throw new IdempotencyKeyInFlightException(key);
        }
    }
}
