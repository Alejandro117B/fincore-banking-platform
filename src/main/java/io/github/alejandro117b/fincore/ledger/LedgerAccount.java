package io.github.alejandro117b.fincore.ledger;

import java.time.Instant;
import java.util.UUID;

import io.github.alejandro117b.fincore.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", updatable = false)
    private Account account;

    @Column(name = "system_code", length = 50, updatable = false)
    private String systemCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private LedgerAccountCategory category;

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private String currencyCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerAccount() {
    }

    private LedgerAccount(Account account, String systemCode, LedgerAccountCategory category,
                          String currencyCode, Instant at) {
        this.id = UUID.randomUUID();
        this.account = account;
        this.systemCode = systemCode;
        this.category = category;
        this.currencyCode = LedgerValidation.currencyCode(currencyCode);
        this.createdAt = LedgerValidation.timestamp(at);
    }

    public static LedgerAccount forAccount(Account account, Instant at) {
        if (account == null) {
            throw new IllegalArgumentException("Bank account is required");
        }
        return new LedgerAccount(account, null, LedgerAccountCategory.LIABILITY, account.getCurrencyCode(), at);
    }

    public static LedgerAccount internal(String systemCode, LedgerAccountCategory category,
                                         String currencyCode, Instant at) {
        if (category == null || systemCode == null || !systemCode.matches("[A-Z][A-Z0-9_]{0,49}")) {
            throw new IllegalArgumentException("Category and an uppercase system code of up to 50 characters are required");
        }
        return new LedgerAccount(null, systemCode, category, currencyCode, at);
    }

    public UUID getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public String getSystemCode() {
        return systemCode;
    }

    public LedgerAccountCategory getCategory() {
        return category;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
