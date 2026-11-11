package com.freightflow.banking.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    static {
        // Controllers take the caller as a parameter built from the token; it is not part of the API.
        org.springdoc.core.utils.SpringDocUtils.getConfig()
                .addRequestWrapperToIgnore(com.freightflow.banking.security.Caller.class);
    }

    @Bean
    public OpenAPI bankingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FreightFlow Banking Service API")
                        .version("1.0.0")
                        .description("Multi-currency accounts (NAD, ZAR, EUR), append-only double-entry ledger, "
                                + "idempotent transfers and FX quotes on the Walvis Bay to Hamburg corridor.")
                        .contact(new Contact().name("FreightFlow").url("https://github.com/tsegale/cross-border-logistics-system")))
                .servers(List.of(new Server().url("/").description("Default server (via gateway or direct)")));
    }

    /**
     * RFC 9457 problems are extensible: each type adds its own members (holdStatus, availableBalance, ...). Say so
     * in the contract, so a consumer validating against it (ADR 0014) accepts them.
     */
    @Bean
    public OpenApiCustomizer problemDetailsAreExtensible() {
        return openApi -> {
            if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
                Schema<?> problem = openApi.getComponents().getSchemas().get("ProblemDetail");
                if (problem != null) {
                    problem.setAdditionalProperties(Boolean.TRUE);
                    if (problem.getProperties() != null) {
                        problem.getProperties().remove("properties");
                    }
                }
            }
        };
    }
}
