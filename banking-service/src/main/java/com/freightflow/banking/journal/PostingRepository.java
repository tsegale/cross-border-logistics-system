package com.freightflow.banking.journal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    List<Posting> findByJournalEntryIdOrderByIdAsc(UUID journalEntryId);
}
