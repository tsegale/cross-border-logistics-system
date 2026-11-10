package com.freightflow.logistics.banking;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * The parts of banking-service's API that logistics calls. The contract is banking's committed OpenAPI document
 * (docs/api/banking-openapi.json); these types declare only the fields logistics reads (ADR 0002).
 */
@HttpExchange(url = "/api/v1", accept = "application/json")
public interface BankingApi {

    @GetExchange("/fx-rates")
    FxRates fxRates(@RequestParam("base") String base);
}
