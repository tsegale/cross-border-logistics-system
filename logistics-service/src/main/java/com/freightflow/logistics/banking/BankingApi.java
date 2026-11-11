package com.freightflow.logistics.banking;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.UUID;

/**
 * The parts of banking-service's API that logistics calls. The contract is banking's committed OpenAPI document
 * (docs/api/banking-openapi.json); these types declare only the fields logistics reads (ADR 0002, ADR 0014).
 * Holds are internal endpoints, reached on the compose network, never through the gateway.
 */
@HttpExchange(url = "/api/v1", accept = "application/json")
public interface BankingApi {

    @GetExchange("/fx-rates")
    FxRates fxRates(@RequestParam("base") String base);

    @PostExchange(url = "/holds", contentType = "application/json")
    Holds.Hold createHold(@RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Holds.CreateHold body);

    @PostExchange(url = "/holds/{id}/capture", contentType = "application/json")
    Holds.Hold capture(@PathVariable("id") UUID holdId, @RequestHeader("Idempotency-Key") String idempotencyKey,
                       @RequestBody Holds.CaptureHold body);

    @PostExchange("/holds/{id}/release")
    Holds.Hold release(@PathVariable("id") UUID holdId, @RequestHeader("Idempotency-Key") String idempotencyKey);
}
