package io.github.alejandro117b.fincore.account;

import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

import io.github.alejandro117b.fincore.customer.Customer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private String currencyCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Account() {
        // Required by JPA.
    }

    private Account(Customer customer, AccountType type, String currencyCode, Instant at) {
        if (customer == null || type == null || at == null) {
            throw new IllegalArgumentException("Customer, account type and creation time are required");
        }
        this.currencyCode = validateCurrencyCode(currencyCode);
        this.id = UUID.randomUUID();
        this.customer = customer;
        this.type = type;
        this.status = AccountStatus.ACTIVE;
        this.createdAt = at;
        this.updatedAt = at;
    }

    public static Account create(Customer customer, AccountType type, String currencyCode, Instant at) {
        return new Account(customer, type, currencyCode, at);
    }

    public void block(Instant at) {
        requireStatus(AccountStatus.ACTIVE);
        validateTransitionTime(at);
        status = AccountStatus.BLOCKED;
        updatedAt = at;
    }

    public void unblock(Instant at) {
        requireStatus(AccountStatus.BLOCKED);
        validateTransitionTime(at);
        status = AccountStatus.ACTIVE;
        updatedAt = at;
    }

    public void close(Instant at) {
        if (status == AccountStatus.CLOSED) {
            throw new IllegalStateException("A closed account cannot change state");
        }
        validateTransitionTime(at);
        status = AccountStatus.CLOSED;
        closedAt = at;
        updatedAt = at;
    }

    private void requireStatus(AccountStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected account status " + expected + " but was " + status);
        }
    }

    private void validateTransitionTime(Instant at) {
        if (at == null || at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Transition time must not precede the previous update");
        }
    }

    private static String validateCurrencyCode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Currency code is required");
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency code must contain three ASCII letters");
        }
        Currency.getInstance(normalized);
        return normalized;
    }

    public UUID getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public AccountType getType() {
        return type;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Long getVersion() {
        return version;
    }
}
