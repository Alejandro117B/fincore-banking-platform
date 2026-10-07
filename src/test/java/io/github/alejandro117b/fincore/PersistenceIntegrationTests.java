package io.github.alejandro117b.fincore;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.alejandro117b.fincore.account.Account;
import io.github.alejandro117b.fincore.account.AccountRepository;
import io.github.alejandro117b.fincore.account.AccountStatus;
import io.github.alejandro117b.fincore.account.AccountType;
import io.github.alejandro117b.fincore.customer.Customer;
import io.github.alejandro117b.fincore.customer.CustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PersistenceIntegrationTests {

    private static final Instant CREATED_AT = Instant.parse("2026-10-06T12:00:00.123456Z");

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void persistsAndReloadsCustomerWithVersionAndTimestamps() {
        Customer customer = Customer.create("Ana María", "López García", "ana@example.com", CREATED_AT);
        assertThat(customer.getVersion()).isNull();
        customers.saveAndFlush(customer);
        assertThat(customer.getVersion()).isNotNull().isNotNegative();
        entityManager.clear();

        Customer reloaded = customers.findById(customer.getId()).orElseThrow();
        assertThat(reloaded).isNotSameAs(customer);
        assertThat(reloaded.getFirstName()).isEqualTo("Ana María");
        assertThat(reloaded.getLastName()).isEqualTo("López García");
        assertThat(reloaded.getEmail()).isEqualTo("ana@example.com");
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(CREATED_AT);
        Long originalVersion = reloaded.getVersion();
        reloaded.updateContactDetails("Ana", "López", null, CREATED_AT.plusSeconds(1));
        customers.flush();
        entityManager.clear();
        Customer updated = customers.findById(customer.getId()).orElseThrow();
        assertThat(updated.getEmail()).isNull();
        assertThat(updated.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(updated.getUpdatedAt()).isEqualTo(CREATED_AT.plusSeconds(1));
        assertThat(updated.getVersion()).isEqualTo(originalVersion + 1);
    }

    @Test
    void allowsSharedContactEmail() {
        customers.saveAndFlush(Customer.create("Ana", "López", "shared@example.com", CREATED_AT));
        customers.saveAndFlush(Customer.create("Luis", "Pérez", "shared@example.com", CREATED_AT));
    }

    @Test
    void persistsMultipleAccountsAndLoadsOwnerLazily() {
        Customer customer = customers.saveAndFlush(Customer.create("Ana", "López", null, CREATED_AT));
        Account checking = accounts.saveAndFlush(Account.create(customer, AccountType.CHECKING, "mxn", CREATED_AT));
        Account savings = accounts.saveAndFlush(Account.create(customer, AccountType.SAVINGS, "usd", CREATED_AT));
        entityManager.clear();

        Account reloaded = accounts.findById(checking.getId()).orElseThrow();
        assertThat(reloaded).isNotSameAs(checking);
        assertThat(Hibernate.isInitialized(reloaded.getCustomer())).isFalse();
        assertThat(reloaded.getCustomer().getId()).isEqualTo(customer.getId());
        assertThat(reloaded.getCustomer().getFirstName()).isEqualTo("Ana");
        assertThat(reloaded.getType()).isEqualTo(AccountType.CHECKING);
        assertThat(reloaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(reloaded.getCurrencyCode()).isEqualTo("MXN");
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getClosedAt()).isNull();
        assertThat(reloaded.getVersion()).isNotNull().isNotNegative();
        assertThat(accounts.findAllByCustomerId(customer.getId()))
                .extracting(Account::getId).containsExactlyInAnyOrder(checking.getId(), savings.getId());
        assertThat(accounts.findById(savings.getId()).orElseThrow().getType()).isEqualTo(AccountType.SAVINGS);
        assertThat(jdbc.queryForObject("select currency_code from accounts where id = ?",
                String.class, savings.getId())).isEqualTo("USD");
    }

    @Test
    void persistsAccountTransitionsAndClosure() {
        Customer customer = customers.saveAndFlush(Customer.create("Ana", "López", null, CREATED_AT));
        Account account = accounts.saveAndFlush(Account.create(customer, AccountType.CHECKING, "MXN", CREATED_AT));
        UUID id = account.getId();
        Long initialVersion = account.getVersion();
        account.block(CREATED_AT.plusSeconds(1));
        accounts.flush();
        entityManager.clear();
        Account blocked = accounts.findById(id).orElseThrow();
        assertThat(blocked.getStatus()).isEqualTo(AccountStatus.BLOCKED);
        assertThat(blocked.getVersion()).isEqualTo(initialVersion + 1);
        blocked.unblock(CREATED_AT.plusSeconds(2));
        accounts.flush();
        entityManager.clear();
        Account active = accounts.findById(id).orElseThrow();
        assertThat(active.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        active.close(CREATED_AT.plusSeconds(3));
        accounts.flush();
        entityManager.clear();
        Account closed = accounts.findById(id).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(closed.getClosedAt()).isEqualTo(CREATED_AT.plusSeconds(3));
        assertThat(closed.getUpdatedAt()).isEqualTo(closed.getClosedAt());
        assertThat(closed.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(jdbc.queryForObject("select status from accounts where id = ?", String.class, id))
                .isEqualTo("CLOSED");
    }

    @Test
    void databaseRestrictsDeletingCustomerWithAccounts() {
        Customer customer = customers.saveAndFlush(Customer.create("Ana", "López", null, CREATED_AT));
        accounts.saveAndFlush(Account.create(customer, AccountType.CHECKING, "MXN", CREATED_AT));
        assertSqlViolation(() -> jdbc.update("delete from customers where id = ?", customer.getId()), "23001");
    }

    @Test
    void deletingAccountDoesNotDeleteCustomer() {
        Customer customer = customers.saveAndFlush(Customer.create("Ana", "López", null, CREATED_AT));
        Account account = accounts.saveAndFlush(Account.create(customer, AccountType.CHECKING, "MXN", CREATED_AT));
        accounts.delete(account);
        accounts.flush();
        entityManager.clear();
        assertThat(accounts.findById(account.getId())).isEmpty();
        assertThat(customers.findById(customer.getId())).isPresent();
    }

    @ParameterizedTest(name = "customers rejects {0} = {1}")
    @MethodSource("invalidCustomerColumns")
    void enforcesCustomerConstraintsEvenForDirectSql(String column, Object invalidValue, String sqlState) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", UUID.randomUUID());
        row.put("first_name", "Ana");
        row.put("last_name", "López");
        row.put("email", null);
        row.put("created_at", sqlTime(CREATED_AT));
        row.put("updated_at", sqlTime(CREATED_AT));
        row.put("version", 0L);
        row.put(column, invalidValue);
        assertSqlViolation(() -> jdbc.update(
                "insert into customers (id, first_name, last_name, email, created_at, updated_at, version) "
                        + "values (?, ?, ?, ?, ?, ?, ?)", row.values().toArray()), sqlState);
    }

    static Stream<Arguments> invalidCustomerColumns() {
        return Stream.of(
                Arguments.of("id", null, "23502"),
                Arguments.of("first_name", null, "23502"),
                Arguments.of("first_name", " \t ", "23514"),
                Arguments.of("first_name", "a".repeat(101), "22001"),
                Arguments.of("last_name", null, "23502"),
                Arguments.of("last_name", "", "23514"),
                Arguments.of("last_name", "b".repeat(151), "22001"),
                Arguments.of("email", " ", "23514"),
                Arguments.of("email", "a".repeat(255), "22001"),
                Arguments.of("created_at", null, "23502"),
                Arguments.of("updated_at", null, "23502"),
                Arguments.of("updated_at", sqlTime(CREATED_AT.minusSeconds(1)), "23514"),
                Arguments.of("version", null, "23502"),
                Arguments.of("version", -1L, "23514"));
    }

    @ParameterizedTest(name = "accounts rejects {0} = {1}")
    @MethodSource("invalidAccountColumns")
    void enforcesAccountConstraintsEvenForDirectSql(String column, Object invalidValue, String sqlState) {
        Customer customer = customers.saveAndFlush(Customer.create("Ana", "López", null, CREATED_AT));
        var row = new LinkedHashMap<String, Object>();
        row.put("id", UUID.randomUUID());
        row.put("customer_id", customer.getId());
        row.put("type", "CHECKING");
        row.put("status", "ACTIVE");
        row.put("currency_code", "MXN");
        row.put("created_at", sqlTime(CREATED_AT));
        row.put("updated_at", sqlTime(CREATED_AT));
        row.put("closed_at", null);
        row.put("version", 0L);
        row.put(column, invalidValue);
        assertSqlViolation(() -> jdbc.update(
                "insert into accounts (id, customer_id, type, status, currency_code, created_at, "
                        + "updated_at, closed_at, version) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.values().toArray()), sqlState);
    }

    static Stream<Arguments> invalidAccountColumns() {
        return Stream.of(
                Arguments.of("id", null, "23502"),
                Arguments.of("customer_id", null, "23502"),
                Arguments.of("customer_id", UUID.randomUUID(), "23503"),
                Arguments.of("type", null, "23502"),
                Arguments.of("type", "UNKNOWN", "23514"),
                Arguments.of("status", null, "23502"),
                Arguments.of("status", "UNKNOWN", "23514"),
                Arguments.of("status", "CLOSED", "23514"),
                Arguments.of("currency_code", null, "23502"),
                Arguments.of("currency_code", "mxn", "23514"),
                Arguments.of("currency_code", "12X", "23514"),
                Arguments.of("currency_code", "US", "23514"),
                Arguments.of("created_at", null, "23502"),
                Arguments.of("updated_at", null, "23502"),
                Arguments.of("updated_at", sqlTime(CREATED_AT.minusSeconds(1)), "23514"),
                Arguments.of("closed_at", sqlTime(CREATED_AT), "23514"),
                Arguments.of("version", null, "23502"),
                Arguments.of("version", -1L, "23514"));
    }

    @Test
    void rejectsClosureTimeBeforeCreation() {
        Customer customer = customers.saveAndFlush(Customer.create("Ana", "López", null, CREATED_AT));
        Account account = accounts.saveAndFlush(Account.create(customer, AccountType.CHECKING, "MXN", CREATED_AT));
        assertSqlViolation(() -> jdbc.update(
                "update accounts set status = 'CLOSED', closed_at = ? where id = ?",
                sqlTime(CREATED_AT.minusSeconds(1)), account.getId()), "23514");
    }

    @Test
    void rejectsClosureTimeAfterLastUpdate() {
        Customer customer = customers.saveAndFlush(Customer.create("Ana", "López", null, CREATED_AT));
        Account account = accounts.saveAndFlush(Account.create(customer, AccountType.CHECKING, "MXN", CREATED_AT));
        assertSqlViolation(() -> jdbc.update(
                "update accounts set status = 'CLOSED', closed_at = ? where id = ?",
                sqlTime(CREATED_AT.plusSeconds(1)), account.getId()), "23514");
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer", "account"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsStaleConcurrentUpdates(String target) {
        Customer customer = Customer.create("Ana", "López", null, CREATED_AT);
        Account account = Account.create(customer, AccountType.CHECKING, "MXN", CREATED_AT);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        try {
            transactions.executeWithoutResult(status -> {
                customers.saveAndFlush(customer);
                accounts.saveAndFlush(account);
            });
            first.getTransaction().begin();
            second.getTransaction().begin();
            Long initialVersion;
            if (target.equals("customer")) {
                Customer winner = first.find(Customer.class, customer.getId());
                Customer stale = second.find(Customer.class, customer.getId());
                initialVersion = winner.getVersion();
                winner.updateContactDetails("Winner", "López", null, CREATED_AT.plusSeconds(1));
                first.getTransaction().commit();
                stale.updateContactDetails("Stale", "López", null, CREATED_AT.plusSeconds(2));
            } else {
                Account winner = first.find(Account.class, account.getId());
                Account stale = second.find(Account.class, account.getId());
                initialVersion = winner.getVersion();
                winner.block(CREATED_AT.plusSeconds(1));
                first.getTransaction().commit();
                stale.close(CREATED_AT.plusSeconds(2));
            }
            assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
            second.getTransaction().rollback();
            transactions.executeWithoutResult(status -> {
                if (target.equals("customer")) {
                    Customer saved = customers.findById(customer.getId()).orElseThrow();
                    assertThat(saved.getFirstName()).isEqualTo("Winner");
                    assertThat(saved.getVersion()).isEqualTo(initialVersion + 1);
                } else {
                    Account saved = accounts.findById(account.getId()).orElseThrow();
                    assertThat(saved.getStatus()).isEqualTo(AccountStatus.BLOCKED);
                    assertThat(saved.getClosedAt()).isNull();
                    assertThat(saved.getVersion()).isEqualTo(initialVersion + 1);
                }
            });
        } finally {
            if (first.getTransaction().isActive()) {
                first.getTransaction().rollback();
            }
            if (second.getTransaction().isActive()) {
                second.getTransaction().rollback();
            }
            first.close();
            second.close();
            // Concurrency needs committed fixtures; remove only these UUIDs, account first.
            transactions.executeWithoutResult(status -> {
                jdbc.update("delete from accounts where id = ?", account.getId());
                jdbc.update("delete from customers where id = ?", customer.getId());
            });
        }
    }

    private static OffsetDateTime sqlTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static void assertSqlViolation(Runnable operation, String sqlState) {
        assertThatThrownBy(operation::run).isInstanceOf(DataAccessException.class)
                .satisfies(exception -> {
                    Throwable cause = ((DataAccessException) exception).getMostSpecificCause();
                    assertThat(cause).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo(sqlState);
                });
    }
}
