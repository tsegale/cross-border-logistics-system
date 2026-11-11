package com.freightflow.gateway.edge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Refuses request bodies over {@code freightflow.gateway.max-request-size} (default 1 MB, ADR 0015) with a 413
 * problem: at once when {@code Content-Length} says so, and while reading when a chunked body grows past it.
 */
@Component
public class RequestSizeFilter implements WebFilter, Ordered {

    private final long maxBytes;

    public RequestSizeFilter(@Value("${freightflow.gateway.max-request-size:1MB}") DataSize maxRequestSize) {
        this.maxBytes = maxRequestSize.toBytes();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long declared = exchange.getRequest().getHeaders().getContentLength();
        if (declared > maxBytes) {
            return tooLarge(exchange);
        }
        if (declared >= 0) {
            return chain.filter(exchange);
        }
        AtomicLong read = new AtomicLong();
        ServerHttpRequest counting = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return super.getBody().map(buffer -> {
                    if (read.addAndGet(buffer.readableByteCount()) > maxBytes) {
                        throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                                "Request body exceeds " + maxBytes + " bytes");
                    }
                    return buffer;
                });
            }
        };
        return chain.filter(exchange.mutate().request(counting).build());
    }

    private Mono<Void> tooLarge(ServerWebExchange exchange) {
        return Problems.write(exchange, HttpStatus.CONTENT_TOO_LARGE, "request-too-large", "Request too large",
                "Request bodies are limited to " + maxBytes + " bytes");
    }

    @Override
    public int getOrder() {
        return RequestIdFilter.ORDER + 2;
    }
}
