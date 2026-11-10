package com.freightflow.logistics.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=32",
        "spring.datasource.hikari.connection-timeout=15000"
})
@AutoConfigureMockMvc
@ImportTestcontainers(SharedContainers.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void bankingStub(DynamicPropertyRegistry registry) {
        registry.add("spring.http.serviceclient.banking.base-url", BankingStub::baseUrl);
    }

    @AfterEach
    void resetBankingStubs() {
        BankingStub.SERVER.resetAll();
    }
}
