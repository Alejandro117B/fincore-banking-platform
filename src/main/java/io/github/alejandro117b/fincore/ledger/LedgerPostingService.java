package io.github.alejandro117b.fincore.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerPostingService {

    private final EntityManager entityManager;

    public LedgerPostingService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    public JournalTransaction post(JournalTransaction journal, List<LedgerEntry> proposedEntries, Instant at) {
        // Validate the same snapshot that will be persisted, not a caller-owned mutable list.
        List<LedgerEntry> entries = proposedEntries == null ? null : new ArrayList<>(proposedEntries);
        validate(journal, entries);
        journal.validatePostingTime(at);
        entityManager.persist(journal);
        entries.forEach(entityManager::persist);
        // Insert every line while the header is DRAFT; never rely on update ordering.
        entityManager.flush();
        journal.markPosted(at);
        entityManager.flush();
        return journal;
    }

    static void validate(JournalTransaction journal, List<LedgerEntry> entries) {
        if (journal == null || entries == null || entries.size() < 2) {
            throw new IllegalArgumentException("A journal requires at least two entries");
        }
        if (journal.getStatus() != JournalStatus.DRAFT) {
            throw new IllegalStateException("Only a draft journal can be posted");
        }
        Set<UUID> accounts = new HashSet<>();
        Set<Integer> lines = new HashSet<>();
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        boolean hasDebit = false;
        boolean hasCredit = false;
        for (LedgerEntry entry : entries) {
            if (entry == null || !journal.getId().equals(entry.getJournalTransaction().getId())) {
                throw new IllegalArgumentException("Every entry must belong to this journal");
            }
            if (!journal.getCurrencyCode().equals(entry.getCurrencyCode())
                    || !journal.getCurrencyCode().equals(entry.getLedgerAccount().getCurrencyCode())) {
                throw new IllegalArgumentException("All entries must share the journal currency");
            }
            if (!lines.add(entry.getLineNumber())) {
                throw new IllegalArgumentException("Line numbers must be unique within a journal");
            }
            accounts.add(entry.getLedgerAccount().getId());
            BigDecimal amount = LedgerEntry.normalizeAmount(entry.getAmount());
            if (entry.getSide() == EntrySide.DEBIT) {
                hasDebit = true;
                debits = debits.add(amount);
            } else if (entry.getSide() == EntrySide.CREDIT) {
                hasCredit = true;
                credits = credits.add(amount);
            } else {
                throw new IllegalArgumentException("Entry side is required");
            }
        }
        if (accounts.size() < 2 || !hasDebit || !hasCredit || debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("Journal must balance debits and credits across at least two ledger accounts");
        }
    }
}
