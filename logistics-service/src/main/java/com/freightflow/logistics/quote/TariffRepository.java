package com.freightflow.logistics.quote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TariffRepository extends JpaRepository<Tariff, UUID> {

    Optional<Tariff> findByOriginUnlocodeAndDestinationUnlocodeAndMode(
            String originUnlocode,
            String destinationUnlocode,
            TransportMode mode
    );
}
