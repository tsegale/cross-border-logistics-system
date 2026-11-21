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

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
