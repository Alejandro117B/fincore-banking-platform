package io.github.alejandro117b.fincore.account;

import java.time.Instant;

import io.github.alejandro117b.fincore.customer.Customer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class AccountTests {

    private static final Instant CREATED_AT = Instant.parse("2026-10-06T12:00:00Z");

    private Customer customer() {
        return Customer.create("Ana", "López", null, CREATED_AT);
    }

    @ParameterizedTest
    @EnumSource(AccountType.class)
    void createsActiveAccountWithNormalizedCurrency(AccountType type) {
        Customer customer = customer();
        Account account = Account.create(customer, type, " mxn ", CREATED_AT);

        assertThat(account.getId()).isNotNull();
        assertThat(account.getCustomer()).isSameAs(customer);
        assertThat(account.getType()).isEqualTo(type);
        assertThat(account.getCurrencyCode()).isEqualTo("MXN");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(account.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(account.getClosedAt()).isNull();
        assertThat(account.getVersion()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "eur", "JPY"})
    void acceptsRecognizedCurrencyCodes(String code) {
        assertThat(Account.create(customer(), AccountType.CHECKING, code, CREATED_AT).getCurrencyCode())
                .hasSize(3);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "ZZZ", "US", "USDD", "123", "M X", "€UR"})
    void rejectsInvalidCurrencyCodes(String code) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> Account.create(customer(), AccountType.CHECKING, code, CREATED_AT));
    }

    @Test
    void rejectsMissingCustomerTypeOrTime() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> Account.create(null, AccountType.CHECKING, "MXN", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(
                () -> Account.create(customer(), null, "MXN", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(
                () -> Account.create(customer(), AccountType.CHECKING, "MXN", null));
    }

    @Test
    void blocksAndUnblocksUsingSuppliedTimes() {
        Account account = Account.create(customer(), AccountType.CHECKING, "MXN", CREATED_AT);
        account.block(CREATED_AT.plusSeconds(1));
        assertThat(account.getStatus()).isEqualTo(AccountStatus.BLOCKED);
        assertThat(account.getClosedAt()).isNull();
        assertThat(account.getUpdatedAt()).isEqualTo(CREATED_AT.plusSeconds(1));
        account.unblock(CREATED_AT.plusSeconds(2));
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getClosedAt()).isNull();
        assertThat(account.getUpdatedAt()).isEqualTo(CREATED_AT.plusSeconds(2));
        assertThat(account.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void rejectsTransitionsFromTheWrongState() {
        Account account = Account.create(customer(), AccountType.CHECKING, "MXN", CREATED_AT);
        assertThatIllegalStateException().isThrownBy(() -> account.unblock(CREATED_AT));
        account.block(CREATED_AT);
        assertThatIllegalStateException().isThrownBy(() -> account.block(CREATED_AT));
        assertThat(account.getStatus()).isEqualTo(AccountStatus.BLOCKED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closesActiveOrBlockedAccountAndNeverReopens(boolean blocked) {
        Account account = Account.create(customer(), AccountType.SAVINGS, "MXN", CREATED_AT);
        if (blocked) {
            account.block(CREATED_AT.plusSeconds(1));
        }
        Instant closedAt = CREATED_AT.plusSeconds(2);
        account.close(closedAt);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.getClosedAt()).isEqualTo(closedAt);
        assertThat(account.getUpdatedAt()).isEqualTo(closedAt);
        assertThatIllegalStateException().isThrownBy(() -> account.unblock(closedAt.plusSeconds(1)));
        assertThatIllegalStateException().isThrownBy(() -> account.block(closedAt.plusSeconds(1)));
        assertThatIllegalStateException().isThrownBy(() -> account.close(closedAt.plusSeconds(1)));
        assertThat(account.getClosedAt()).isEqualTo(closedAt);
        assertThat(account.getUpdatedAt()).isEqualTo(closedAt);
    }

    @Test
    void rejectsNullOrRegressingTimesWithoutChangingState() {
        Account account = Account.create(customer(), AccountType.CHECKING, "MXN", CREATED_AT);
        assertThatIllegalArgumentException().isThrownBy(() -> account.block(null));
        assertThatIllegalArgumentException().isThrownBy(() -> account.close(CREATED_AT.minusSeconds(1)));
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getClosedAt()).isNull();
        account.block(CREATED_AT.plusSeconds(10));
        assertThatIllegalArgumentException().isThrownBy(() -> account.unblock(CREATED_AT.plusSeconds(5)));
        assertThatIllegalArgumentException().isThrownBy(() -> account.unblock(null));
        assertThatIllegalArgumentException().isThrownBy(() -> account.close(CREATED_AT.plusSeconds(5)));
        assertThatIllegalArgumentException().isThrownBy(() -> account.close(null));
        assertThat(account.getStatus()).isEqualTo(AccountStatus.BLOCKED);
        assertThat(account.getUpdatedAt()).isEqualTo(CREATED_AT.plusSeconds(10));
        assertThat(account.getClosedAt()).isNull();
    }
}
