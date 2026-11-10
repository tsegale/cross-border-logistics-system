package com.freightflow.logistics.idempotency;

import com.freightflow.logistics.common.LockTimeouts;
import com.freightflow.logistics.config.LogisticsProperties;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
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

@Service
public class IdempotencyService {

    private static final int MAX_KEY_LENGTH = 128;
    private static final JsonMapper CANONICAL_JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final IdempotencyRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final LogisticsProperties properties;
    private final Clock clock;

    public IdempotencyService(
            IdempotencyRepository repository,
            JdbcTemplate jdbcTemplate,
            LogisticsProperties properties,
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

    /** The timeout applies to this one lock only; it must not leak into later statements of the transaction. */
    private void acquireAdvisoryLock(String scope, String key) {
        try {
            LockTimeouts.withLockTimeout(jdbcTemplate, properties.idempotency().lockTimeout(), () ->
                    jdbcTemplate.queryForObject(
                            "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))::text",
                            String.class,
                            scope,
                            key
                    ));
        } catch (PessimisticLockingFailureException ex) {
            throw new IdempotencyKeyInFlightException(key);
        }
    }
}
