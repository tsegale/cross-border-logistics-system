package com.freightflow.logistics.config;

import com.freightflow.logistics.payment.HoldExpiryProperties;
import com.freightflow.logistics.payment.PaymentProperties;
import com.freightflow.logistics.events.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({com.freightflow.logistics.dcsa.DcsaProperties.class, LogisticsProperties.class, PaymentProperties.class, HoldExpiryProperties.class,
        OutboxProperties.class})
@EnableScheduling
@EnableResilientMethods
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
