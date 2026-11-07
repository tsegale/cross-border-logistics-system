package com.freightflow.banking.journal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    boolean existsByReversesEntryId(UUID reversesEntryId);

    Optional<JournalEntry> findByReversesEntryId(UUID reversesEntryId);
}
