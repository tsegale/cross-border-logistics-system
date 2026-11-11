package com.freightflow.logistics.banking;

import com.freightflow.logistics.common.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

/**
 * Forwards the current {@code requestId} to banking-service as {@code X-Request-Id} (ADR 0015), so one id follows a
 * request across both services' logs. The payment relay sets it to the command's idempotency key.
 */
@Configuration(proxyBeanMethods = false)
public class RequestIdPropagation {

    static final ClientHttpRequestInterceptor FORWARD_REQUEST_ID = (request, body, execution) -> {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (RequestIdFilter.valid(requestId) && !request.getHeaders().containsHeader(RequestIdFilter.HEADER)) {
            request.getHeaders().set(RequestIdFilter.HEADER, requestId);
        }
        return execution.execute(request, body);
    };

    @Bean
    RestClientHttpServiceGroupConfigurer bankingRequestIds() {
        return groups -> groups.filterByName("banking")
                .forEachClient((group, builder) -> builder.requestInterceptor(FORWARD_REQUEST_ID));
    }
}
