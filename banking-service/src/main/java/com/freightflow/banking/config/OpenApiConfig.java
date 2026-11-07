package com.freightflow.banking.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

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
}
