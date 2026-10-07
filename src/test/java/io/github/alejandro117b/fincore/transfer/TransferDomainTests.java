package io.github.alejandro117b.fincore.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.alejandro117b.fincore.account.Account;
import io.github.alejandro117b.fincore.account.AccountType;
import io.github.alejandro117b.fincore.customer.Customer;
import io.github.alejandro117b.fincore.ledger.JournalTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class TransferDomainTests {
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DESTINATION = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @ParameterizedTest
    @MethodSource("validAmounts")
    void currencyGranularityIsExact(String currency, String amount, String expected) {
        assertThat(TransferCurrencyPolicy.normalizeAmount(new BigDecimal(amount), currency))
                .isEqualTo(new BigDecimal(expected));
    }

    static Stream<Arguments> validAmounts() {
        return Stream.of(Arguments.of("MXN", "500.12", "500.1200"),
                Arguments.of("USD", "1.01", "1.0100"), Arguments.of("JPY", "500.0000", "500.0000"),
                Arguments.of("MXN", "500.12000", "500.1200"),
                Arguments.of("USD", "999999999999999.99", "999999999999999.9900"));
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void doesNotRoundOrAcceptInvalidAmounts(String currency, String amount) {
        assertThatThrownBy(() -> TransferCurrencyPolicy.normalizeAmount(amount == null ? null : new BigDecimal(amount), currency))
                .isInstanceOf(TransferException.class)
                .extracting(exception -> ((TransferException) exception).getCode()).isEqualTo(TransferErrorCode.INVALID_AMOUNT);
    }

    static Stream<Arguments> invalidAmounts() {
        return Stream.of(Arguments.of("MXN", "500.001"), Arguments.of("USD", "0.001"),
                Arguments.of("JPY", "500.1"), Arguments.of("JPY", "0.01"), Arguments.of("MXN", "0"),
                Arguments.of("MXN", "-1"), Arguments.of("MXN", null),
                Arguments.of("MXN", "1000000000000000"), Arguments.of("MXN", "0.00001"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"EUR", "CAD", "XXX", "ZZZ", "", "US"})
    void onlyExplicitlySupportedCurrenciesAreAccepted(String currency) {
        assertThatThrownBy(() -> TransferCurrencyPolicy.normalizeCurrency(currency))
                .isInstanceOf(TransferException.class)
                .extracting(exception -> ((TransferException) exception).getCode()).isEqualTo(TransferErrorCode.INVALID_CURRENCY);
    }

    @Test
    void currencyNormalizationDoesNotDependOnDefaultLocale() {
        assertThat(TransferCurrencyPolicy.normalizeCurrency(" mxn ")).isEqualTo("MXN");
        assertThat(TransferCurrencyPolicy.normalizeCurrency("usd")).isEqualTo("USD");
    }

    @Test
    void equivalentDecimalRepresentationsHaveTheSameVersionedHash() {
        assertThat(TransferRequestHasher.HASH_VERSION).isEqualTo(1);
        String expected = TransferRequestHasher.hash(SOURCE, DESTINATION, new BigDecimal("500"), "MXN");
        assertThat(expected).matches("[0-9a-f]{64}");
        assertThat(TransferRequestHasher.hash(SOURCE, DESTINATION, new BigDecimal("500.0"), "mxn")).isEqualTo(expected);
        assertThat(TransferRequestHasher.hash(SOURCE, DESTINATION, new BigDecimal("500.00"), "MXN")).isEqualTo(expected);
        assertThat(TransferRequestHasher.hash(SOURCE, DESTINATION, new BigDecimal("500.0000"), "MXN")).isEqualTo(expected);
        // Golden contract vector detects accidental changes to canonical v1 serialization.
        assertThat(expected).isEqualTo("696fd617168dc7d868aa34d34e4760c3696382278997b02491a1208ce2146de7");
    }

    @Test
    void hashChangesOnlyWithEconomicFields() {
        String original = TransferRequestHasher.hash(SOURCE, DESTINATION, new BigDecimal("500"), "MXN");
        assertThat(TransferRequestHasher.hash(DESTINATION, SOURCE, new BigDecimal("500"), "MXN")).isNotEqualTo(original);
        assertThat(TransferRequestHasher.hash(SOURCE, UUID.randomUUID(), new BigDecimal("500"), "MXN")).isNotEqualTo(original);
        assertThat(TransferRequestHasher.hash(SOURCE, DESTINATION, new BigDecimal("501"), "MXN")).isNotEqualTo(original);
        assertThat(TransferRequestHasher.hash(SOURCE, DESTINATION, new BigDecimal("500"), "USD")).isNotEqualTo(original);
        TransferCommand first = TransferService.normalize(command("key-one", "original"));
        TransferCommand second = TransferService.normalize(command("key-two", "different"));
        assertThat(TransferRequestHasher.hash(first.sourceAccountId(), first.destinationAccountId(), first.amount(), first.currencyCode()))
                .isEqualTo(TransferRequestHasher.hash(second.sourceAccountId(), second.destinationAccountId(), second.amount(), second.currencyCode()));
    }

    @Test
    void globalLockOrderIsIndependentOfDirectionAndUsesUnsignedCanonicalUuidOrder() {
        assertThat(TransferLockOrder.ordered(SOURCE, DESTINATION)).containsExactly(SOURCE, DESTINATION);
        assertThat(TransferLockOrder.ordered(DESTINATION, SOURCE)).containsExactly(SOURCE, DESTINATION);
        assertThat(TransferLockOrder.ordered(SOURCE, SOURCE)).containsExactly(SOURCE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "with space", "invalid/key"})
    void rejectsInvalidKeysBeforeReservation(String key) {
        assertThatThrownBy(() -> TransferService.normalize(command(key, null)))
                .isInstanceOf(TransferException.class)
                .extracting(exception -> ((TransferException) exception).getCode()).isEqualTo(TransferErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsMissingIdsAndOversizedOrBlankReferenceBeforeReservation() {
        assertThatThrownBy(() -> TransferService.normalize(null)).isInstanceOf(TransferException.class);
        assertThatThrownBy(() -> TransferService.normalize(new TransferCommand(null, DESTINATION,
                BigDecimal.ONE, "MXN", "key", null))).isInstanceOf(TransferException.class);
        assertThatThrownBy(() -> TransferService.normalize(new TransferCommand(SOURCE, null,
                BigDecimal.ONE, "MXN", "key", null))).isInstanceOf(TransferException.class);
        assertThatThrownBy(() -> TransferService.normalize(command("k".repeat(129), null))).isInstanceOf(TransferException.class);
        assertThatThrownBy(() -> TransferService.normalize(command("key", "r".repeat(129)))).isInstanceOf(TransferException.class);
        assertThatThrownBy(() -> TransferService.normalize(command("key", "  "))).isInstanceOf(TransferException.class);
    }

    @Test
    void aTransferCannotPretendADraftJournalHasCompleted() {
        Instant at = Instant.parse("2026-10-07T12:00:00Z");
        Customer customer = Customer.create("Ana", "Perez", null, at);
        Account source = Account.create(customer, AccountType.CHECKING, "MXN", at);
        Account destination = Account.create(customer, AccountType.CHECKING, "MXN", at);
        assertThatIllegalArgumentException().isThrownBy(() -> Transfer.completed(source, destination, BigDecimal.ONE,
                "MXN", null, JournalTransaction.draft("MXN", null, at), at, at));
    }

    private TransferCommand command(String key, String reference) {
        return new TransferCommand(SOURCE, DESTINATION, new BigDecimal("500.00"), "MXN", key, reference);
    }
}
