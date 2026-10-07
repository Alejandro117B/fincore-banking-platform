package io.github.alejandro117b.fincore.ledger;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerBalanceService {

    private final LedgerAccountRepository accounts;
    private final LedgerEntryRepository entries;

    public LedgerBalanceService(LedgerAccountRepository accounts, LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.entries = entries;
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(UUID ledgerAccountId) {
        LedgerAccount account = accounts.findById(ledgerAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger account does not exist"));
        BigDecimal netCredits = entries.netCredits(ledgerAccountId);
        return (account.getCategory() == LedgerAccountCategory.LIABILITY ? netCredits : netCredits.negate())
                .setScale(4);
    }
}
