package com.freightflow.logistics.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * RFC 9562 version 7 UUIDs (48-bit Unix milliseconds, then random bits), for rows inserted with plain SQL. Same
 * layout as Hibernate's {@code UuidGenerator.Style.VERSION_7}, so keys stay time-ordered in the B-tree.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID next(Clock clock) {
        long millis = clock.millis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
