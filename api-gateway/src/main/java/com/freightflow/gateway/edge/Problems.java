package com.freightflow.gateway.edge;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Writes RFC 9457 problem details for errors the gateway itself produces (ADR 0015, docs/problems.md). */
public final class Problems {

    public static final String BASE = "https://freightflow.dev/problems/";
    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Problems() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String slug, String title,
                                   String detail) {
        return write(exchange, status, slug, title, detail, headers -> {
        });
    }

    /** With extra response headers: {@code WWW-Authenticate} on a 401, {@code Retry-After} and limits on a 429. */
    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String slug, String title,
                                   String detail, Consumer<HttpHeaders> extraHeaders) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        ObjectNode body = JSON.createObjectNode()
                .put("type", BASE + slug)
                .put("title", title)
                .put("status", status.value())
                .put("detail", detail)
                .put("instance", exchange.getRequest().getPath().value());
        String requestId = exchange.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        byte[] bytes = JSON.writeValueAsBytes(body);
        response.setStatusCode(status);
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(PROBLEM_JSON);
        headers.setContentLength(bytes.length);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            headers.set(HttpHeaders.RETRY_AFTER, "5");
        }
        extraHeaders.accept(headers);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
