package com.freightflow.gateway.edge;

import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** Every branch of the gateway's own error mapping (ADR 0015), without a running server. */
class GatewayProblemHandlerTest {

    private final GatewayProblemHandler handler = new GatewayProblemHandler();

    @ParameterizedTest
    @CsvSource({
            "404, not-found",
            "413, request-too-large",
            "504, upstream-timeout",
            "503, upstream-unavailable",
            "502, upstream-error",
            "405, method-not-allowed",
            "400, invalid-request",
            "500, internal-error",
            "501, internal-error"
    })
    void statusExceptionsBecomeProblemTypes(int status, String slug) {
        MockServerWebExchange exchange = exchange();
        handler.handle(exchange, new ResponseStatusException(HttpStatus.valueOf(status), "reason")).block();

        assertProblem(exchange, slug);
    }

    @Test
    void unknownHostAndConnectTimeoutAreUpstreamUnavailable() {
        MockServerWebExchange unknownHost = exchange();
        handler.handle(unknownHost, new RuntimeException(new UnknownHostException("banking-service"))).block();
        assertProblem(unknownHost, "upstream-unavailable");
        assertThat(unknownHost.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("5");

        MockServerWebExchange connectTimeout = exchange();
        handler.handle(connectTimeout, new ConnectTimeoutException("connect timed out")).block();
        assertProblem(connectTimeout, "upstream-unavailable");
    }

    @Test
    void timeoutAnywhereInTheCauseChainIsUpstreamTimeout() {
        MockServerWebExchange exchange = exchange();
        handler.handle(exchange, new IllegalStateException(new TimeoutException("slow"))).block();

        assertProblem(exchange, "upstream-timeout");
    }

    @Test
    void anythingElseIsAnInternalErrorWithoutDetails() {
        MockServerWebExchange exchange = exchange();
        handler.handle(exchange, new IllegalStateException("secret")).block();

        assertProblem(exchange, "internal-error");
        assertThat(body(exchange)).doesNotContain("secret");
    }

    @Test
    void committedResponseIsLeftAlone() {
        MockServerWebExchange exchange = exchange();
        exchange.getResponse().setComplete().block();

        StepVerifier.create(handler.handle(exchange, new IllegalStateException("late")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void problemCarriesTheRequestIdAndThePath() {
        MockServerWebExchange exchange = exchange();
        exchange.getAttributes().put(RequestIdFilter.ATTRIBUTE, "req-42");
        handler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND)).block();

        assertThat(body(exchange)).contains("\"requestId\":\"req-42\"").contains("\"instance\":\"/api/v1/x\"");
    }

    @Test
    void chunkedBodyOverTheLimitFailsWhileItIsRead() {
        RequestSizeFilter filter = new RequestSizeFilter(DataSize.ofBytes(8));
        DataBuffer chunk = DefaultDataBufferFactory.sharedInstance.wrap("0123456789".getBytes(StandardCharsets.UTF_8));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/accounts")
                .header("Transfer-Encoding", "chunked")
                .body(Flux.just(chunk)));

        Mono<Void> result = filter.filter(exchange, (ServerWebExchange ex) -> ex.getRequest().getBody().then());

        StepVerifier.create(result)
                .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(413))
                .verify();
    }

    @Test
    void bodyWithinTheLimitPassesThrough() {
        RequestSizeFilter filter = new RequestSizeFilter(DataSize.ofKilobytes(1));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON).body("{}"));

        StepVerifier.create(filter.filter(exchange, ex -> ex.getRequest().getBody().then())).verifyComplete();
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/x"));
    }

    private static void assertProblem(MockServerWebExchange exchange, String slug) {
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(body(exchange)).contains("\"type\":\"https://freightflow.dev/problems/" + slug + "\"");
    }

    private static String body(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block();
    }
}
