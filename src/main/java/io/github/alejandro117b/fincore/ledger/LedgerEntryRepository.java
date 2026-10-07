package io.github.alejandro117b.fincore.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends Repository<LedgerEntry, UUID> {

    List<LedgerEntry> findAllByJournalTransactionIdOrderByLineNumberAsc(UUID journalId);

    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN e.side = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
            FROM ledger_entries e
            JOIN journal_transactions j ON j.id = e.journal_transaction_id
            WHERE e.ledger_account_id = :ledgerAccountId AND j.status = 'POSTED'
            """, nativeQuery = true)
    BigDecimal netCredits(@Param("ledgerAccountId") UUID ledgerAccountId);
}
