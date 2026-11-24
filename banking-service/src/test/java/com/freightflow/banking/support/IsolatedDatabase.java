package com.freightflow.banking.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

/**
 * A database of its own in the shared PostgreSQL container, for a test whose rows other tests must never see and that
 * must never see theirs: reference rates are never deleted (ADR 0034), so tests that store them cannot share
 * {@link IntegrationTest}'s database. Flyway migrates it when the test's context starts.
 */
public final class IsolatedDatabase {

    private final String url;

    private IsolatedDatabase(String url) {
        this.url = url;
    }

    public static IsolatedDatabase create(String purpose) {
        String name = (purpose + "_" + UUID.randomUUID().toString().substring(0, 8)).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
        try (Connection connection = DriverManager.getConnection(SharedContainers.POSTGRES.getJdbcUrl(),
                SharedContainers.POSTGRES.getUsername(), SharedContainers.POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not create test database " + name, ex);
        }
        String base = SharedContainers.POSTGRES.getJdbcUrl();
        int query = base.indexOf('?');
        String withoutQuery = query < 0 ? base : base.substring(0, query);
        String host = withoutQuery.substring(0, withoutQuery.lastIndexOf('/') + 1);
        return new IsolatedDatabase(host + name + (query < 0 ? "" : base.substring(query)));
    }

    public String url() {
        return url;
    }

    public String username() {
        return SharedContainers.POSTGRES.getUsername();
    }

    public String password() {
        return SharedContainers.POSTGRES.getPassword();
    }
}
