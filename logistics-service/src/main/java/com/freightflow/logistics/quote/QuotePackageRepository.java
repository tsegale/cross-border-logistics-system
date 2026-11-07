package com.freightflow.logistics.quote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QuotePackageRepository extends JpaRepository<QuotePackage, UUID> {

    List<QuotePackage> findByQuoteIdOrderByLineNumberAsc(UUID quoteId);
}
