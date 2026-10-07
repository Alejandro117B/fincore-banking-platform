package io.github.alejandro117b.fincore.customer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CustomerTests {

    private static final Instant CREATED_AT = Clock.fixed(
            Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC).instant();

    @Test
    void createsCustomerWithNormalizedNamesAndOptionalEmail() {
        Customer customer = Customer.create("  Ana María  ", "  López García  ", null, CREATED_AT);

        assertThat(customer.getId()).isNotNull();
        assertThat(customer.getFirstName()).isEqualTo("Ana María");
        assertThat(customer.getLastName()).isEqualTo("López García");
        assertThat(customer.getEmail()).isNull();
        assertThat(customer.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(customer.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(customer.getVersion()).isNull();
        assertThat(Customer.create("Ana", "López", "  Ana@example.com  ", CREATED_AT).getEmail())
                .isEqualTo("Ana@example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingNames(String invalidName) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> Customer.create(invalidName, "López", null, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(
                () -> Customer.create("Ana", invalidName, null, CREATED_AT));
    }

    @Test
    void enforcesNameLengthBoundaries() {
        assertThat(Customer.create("a".repeat(100), "b".repeat(150), null, CREATED_AT)).isNotNull();
        assertThatIllegalArgumentException().isThrownBy(
                () -> Customer.create("a".repeat(101), "López", null, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(
                () -> Customer.create("Ana", "b".repeat(151), null, CREATED_AT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "ana", "ana@", "@example.com", "ana@example", "a b@example.com"})
    void rejectsMalformedEmail(String email) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> Customer.create("Ana", "López", email, CREATED_AT));
    }

    @Test
    void rejectsOversizedEmailAndMissingCreationTime() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> Customer.create("Ana", "López", "a".repeat(250) + "@example.com", CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(
                () -> Customer.create("Ana", "López", null, null));
    }

    @Test
    void updatesContactDetailsUsingSuppliedTime() {
        Customer customer = Customer.create("Ana", "López", null, CREATED_AT);
        var id = customer.getId();
        Instant updatedAt = CREATED_AT.plusSeconds(10);

        customer.updateContactDetails("  Ana María ", " López ", "ana@example.com", updatedAt);

        assertThat(customer.getFirstName()).isEqualTo("Ana María");
        assertThat(customer.getEmail()).isEqualTo("ana@example.com");
        assertThat(customer.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(customer.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(customer.getId()).isEqualTo(id);
        assertThat(customer.getVersion()).isNull();
    }

    @Test
    void rejectedUpdateDoesNotPartiallyChangeCustomer() {
        Customer customer = Customer.create("Ana", "López", null, CREATED_AT);
        assertThatIllegalArgumentException().isThrownBy(
                () -> customer.updateContactDetails("Changed", "López", "invalid", CREATED_AT.plusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(
                () -> customer.updateContactDetails("Changed", "López", null, CREATED_AT.minusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(
                () -> customer.updateContactDetails("Changed", "López", null, null));
        assertThat(customer.getFirstName()).isEqualTo("Ana");
        assertThat(customer.getEmail()).isNull();
        assertThat(customer.getUpdatedAt()).isEqualTo(CREATED_AT);
    }
}
