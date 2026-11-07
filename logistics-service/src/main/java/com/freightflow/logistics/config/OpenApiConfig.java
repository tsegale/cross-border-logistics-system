package com.freightflow.logistics.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI logisticsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FreightFlow Logistics Service API")
                        .version("0.1.0")
                        .description("Freight quotes, shipments and tracking on the Walvis Bay to Hamburg corridor"))
                .servers(List.of(new Server().url("/")));
    }
}
