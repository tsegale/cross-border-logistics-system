package com.freightflow.logistics.banking;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/** Base URL and timeouts come from {@code spring.http.serviceclient.banking.*}. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "banking", types = BankingApi.class)
public class BankingClientConfig {
}
