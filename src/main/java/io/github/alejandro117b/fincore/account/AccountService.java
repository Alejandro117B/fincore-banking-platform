package io.github.alejandro117b.fincore.account;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import io.github.alejandro117b.fincore.account.api.AccountApiMapper;
import io.github.alejandro117b.fincore.account.api.AccountBalanceResponse;
import io.github.alejandro117b.fincore.account.api.AccountResponse;
import io.github.alejandro117b.fincore.api.ApiException;
import io.github.alejandro117b.fincore.api.ApiInputs;
import io.github.alejandro117b.fincore.customer.Customer;
import io.github.alejandro117b.fincore.customer.CustomerRepository;
import io.github.alejandro117b.fincore.ledger.LedgerAccount;
import io.github.alejandro117b.fincore.ledger.LedgerAccountRepository;
import io.github.alejandro117b.fincore.ledger.LedgerBalanceService;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final LedgerAccountRepository ledgerAccounts;
    private final LedgerBalanceService balances;
    private final EntityManager em;
    private final Clock clock;

    public AccountService(AccountRepository accounts, CustomerRepository customers, LedgerAccountRepository ledgerAccounts,
                          LedgerBalanceService balances, EntityManager em, Clock clock) {
        this.accounts = accounts;
        this.customers = customers;
        this.ledgerAccounts = ledgerAccounts;
        this.balances = balances;
        this.em = em;
        this.clock = clock;
    }

    @Transactional
    public AccountResponse create(UUID customerId, AccountType type, String currencyCode) {
        Customer owner = customers.findById(customerId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer was not found."));
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        java.util.Objects.requireNonNull(type, "Account type must be supplied by the validated adapter");
        Account account;
        try {
            account = Account.create(owner, type, currencyCode, at);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_CURRENCY", "Account currency is invalid.");
        }
        em.persist(account);
        em.persist(LedgerAccount.forAccount(account, at));
        em.flush();
        return AccountApiMapper.from(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID id) {
        return AccountApiMapper.from(requireAccount(id));
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse balance(UUID id) {
        Account account = requireAccount(id);
        LedgerAccount ledger = requireLedger(id);
        return new AccountBalanceResponse(id, account.getCurrencyCode(),
                ApiInputs.money(balances.getBalance(ledger.getId())), clock.instant());
    }

    Account requireAccount(UUID id) {
        return accounts.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account was not found."));
    }

    LedgerAccount requireLedger(UUID id) {
        return ledgerAccounts.findByAccountId(id).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_READY", "Account is not ready for this operation."));
    }
}
