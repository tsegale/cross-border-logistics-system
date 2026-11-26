package com.freightflow.banking.config;

import com.freightflow.banking.hold.HoldProperties;
import com.freightflow.banking.approval.ApprovalProperties;
import com.freightflow.banking.events.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({BankingProperties.class, HoldProperties.class, ApprovalProperties.class,
        OutboxProperties.class})
public class ClockConfig {

    /**
     * The service's clock, which every time decision and the token lifetime check read. {@code freightflow.clock.offset}
     * moves it, as a host whose clock drifted would: for the clock-skew experiment only (docs/chaos, ADR 0045), zero in
     * every deployment.
     */
    @Bean
    public Clock clock(@org.springframework.beans.factory.annotation.Value("${freightflow.clock.offset:0s}")
                       java.time.Duration offset) {
        return offset.isZero() ? Clock.systemUTC() : Clock.offset(Clock.systemUTC(), offset);
    }
}
