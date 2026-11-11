package com.freightflow.banking.transfer;

import com.freightflow.banking.config.BankingProperties;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Publishes the active transfer locking strategy on {@code GET /actuator/info}, so a benchmark can check which
 * strategy it is measuring before every run instead of trusting a request parameter.
 */
@Component
public class TransferStrategyInfoContributor implements InfoContributor {

    private final BankingProperties properties;

    public TransferStrategyInfoContributor(BankingProperties properties) {
        this.properties = properties;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("transfer", Map.of("lockingStrategy", properties.transferLockingStrategy()));
        builder.withDetail("ledger", Map.of(
                "systemPostingMode", properties.systemAccounts().postingMode().name().toLowerCase(Locale.ROOT),
                "shards", properties.systemAccounts().shards()));
    }
}
