package com.freightflow.banking.common;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void gatewayRequestIdIsInTheLogContextDuringTheRequestAndEchoed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/x");
        request.addHeader("X-Request-Id", "gw-1234");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (FilterChain) (req, res) -> seen.set(MDC.get("requestId")));

        assertThat(seen.get()).isEqualTo("gw-1234");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("gw-1234");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void missingOrInvalidRequestIdIsReplacedWithANewOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("X-Request-Id", "has spaces and is not valid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
    }
}
