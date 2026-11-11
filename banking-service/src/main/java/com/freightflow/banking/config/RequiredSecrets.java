package com.freightflow.banking.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Refuses to start without its secrets (ADR 0019). There are no defaults in application.yml or the image: a value
 * comes from a compose secret file under /run/secrets (read through the config tree) or from the environment. Runs
 * after the configuration is loaded, before anything connects anywhere, so a missing secret is one clear message,
 * not a database or Keycloak authentication error later.
 *
 * <p>Tests that provide connections through Testcontainers set {@code freightflow.secrets.required=false}.
 */
public class RequiredSecrets implements EnvironmentPostProcessor, Ordered {

    static final List<String> REQUIRED = List.of(
            "spring.datasource.password");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("freightflow.secrets.required", Boolean.class, true)) {
            return;
        }
        List<String> missing = REQUIRED.stream().filter(key -> !StringUtils.hasText(environment.getProperty(key))).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing secrets " + missing + ": mount secrets/banking_db_password (compose secret) or set BANKING_DB_PASSWORD. There are no defaults.");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
