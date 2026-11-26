package com.freightflow.gateway.edge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * What a slow client may hold of the public listener (ADR 0042). Boot sets the idle timeout and the header size
 * (application.yml); reactor-netty's read and request timeouts it does not, and without them a client that sends a
 * byte every few seconds keeps its connection, and its buffers, for as long as it likes.
 */
@Configuration(proxyBeanMethods = false)
public class ServerLimits {

    @Bean
    NettyServerCustomizer slowClientTimeouts(@Value("${freightflow.gateway.read-timeout:10s}") Duration readTimeout,
                                             @Value("${freightflow.gateway.request-timeout:30s}") Duration requestTimeout) {
        // Read: the longest silence while a request is read. Request: the longest a whole request may take to arrive.
        return server -> server.readTimeout(readTimeout).requestTimeout(requestTimeout);
    }
}
