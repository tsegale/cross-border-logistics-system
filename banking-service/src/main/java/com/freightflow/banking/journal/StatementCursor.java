package com.freightflow.banking.journal;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset cursor for {@code GET /api/v1/accounts/{id}/postings}, bound to the account id
 * so a cursor from one account cannot be replayed against another.
 */
public record StatementCursor(int v, UUID a, String t, UUID i) {

    static final int VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static StatementCursor after(UUID accountId, Instant createdAt, UUID postingId) {
        return new StatementCursor(VERSION, accountId, createdAt.toString(), postingId);
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(this));
    }

    public static Decoded decode(String token, UUID expectedAccountId) {
        StatementCursor cursor;
        try {
            cursor = JSON.readValue(Base64.getUrlDecoder().decode(token), StatementCursor.class);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new InvalidStatementCursorException("Statement cursor is malformed");
        }
        if (cursor == null || cursor.v() != VERSION || cursor.a() == null || cursor.t() == null || cursor.i() == null) {
            throw new InvalidStatementCursorException("Statement cursor is malformed");
        }
        if (!cursor.a().equals(expectedAccountId)) {
            throw new InvalidStatementCursorException("Statement cursor belongs to a different account");
        }
        try {
            Instant createdAt = Instant.parse(cursor.t());
            return new Decoded(createdAt, cursor.i());
        } catch (DateTimeParseException ex) {
            throw new InvalidStatementCursorException("Statement cursor timestamp is malformed");
        }
    }

    public record Decoded(Instant createdAt, UUID postingId) {
    }
}
