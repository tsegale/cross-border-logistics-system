package com.freightflow.logistics.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The service does not start with its secrets missing, and the defaults are gone from application.yml (ADR 0019). */
class RequiredSecretsTest {

    @Test
    void startingWithoutTheSecretsFailsWithOneClearMessage() {
        assertThatThrownBy(() -> new RequiredSecrets().postProcessEnvironment(new MockEnvironment(), new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing secrets")
                .hasMessageContaining(RequiredSecrets.REQUIRED.getFirst());
    }

    @Test
    void theRealConfigurationHasNoDefaultSecret() {
        // application.yml as packaged, nothing from the environment: every required value must still be missing.
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        org.springframework.boot.env.YamlPropertySourceLoader yaml = new org.springframework.boot.env.YamlPropertySourceLoader();
        try {
            yaml.load("application", new org.springframework.core.io.ClassPathResource("application.yml"))
                    .forEach(environment.getPropertySources()::addLast);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        for (String key : RequiredSecrets.REQUIRED) {
            String value;
            try {
                value = environment.getProperty(key);
            } catch (IllegalArgumentException unresolved) {
                value = null;
            }
            org.assertj.core.api.Assertions.assertThat(value).as("default for %s", key).isNullOrEmpty();
        }
    }

    @Test
    void presentSecretsStart() {
        MockEnvironment environment = new MockEnvironment();
        RequiredSecrets.REQUIRED.forEach(key -> environment.setProperty(key, "set"));
        assertThatCode(() -> new RequiredSecrets().postProcessEnvironment(environment, new SpringApplication()))
                .doesNotThrowAnyException();
    }
}
