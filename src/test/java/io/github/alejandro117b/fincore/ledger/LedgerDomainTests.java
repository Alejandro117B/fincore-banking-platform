package io.github.alejandro117b.fincore.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.github.alejandro117b.fincore.account.Account;
import io.github.alejandro117b.fincore.account.AccountType;
import io.github.alejandro117b.fincore.customer.Customer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class LedgerDomainTests {

    private static final Instant AT = Instant.parse("2026-10-07T12:00:00Z");

    private LedgerAccount internal(String code) {
        return LedgerAccount.internal(code, LedgerAccountCategory.ASSET, "MXN", AT);
    }

    private LedgerEntry entry(JournalTransaction journal, LedgerAccount account, int line, EntrySide side, String amount) {
        return LedgerEntry.create(journal, account, line, side, new BigDecimal(amount));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "0.00001", "1.23456", "1000000000000000", "1E+20"})
    void rejectsNonpositiveInexactOrOversizedAmounts(String value) {
        BigDecimal amount = value == null ? null : new BigDecimal(value);
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        assertThatIllegalArgumentException().isThrownBy(
                () -> LedgerEntry.create(journal, internal("CASH"), 1, EntrySide.DEBIT, amount));
    }

    @Test
    void exactDecimalArithmeticBalancesMultipleLines() {
        JournalTransaction journal = JournalTransaction.draft("mxn", null, AT);
        LedgerAccount a = internal("A");
        LedgerAccount b = internal("B");
        List<LedgerEntry> entries = List.of(
                entry(journal, a, 1, EntrySide.DEBIT, "0.1"),
                entry(journal, a, 2, EntrySide.DEBIT, "0.20"),
                entry(journal, b, 3, EntrySide.CREDIT, "0.30000"));
        LedgerPostingService.validate(journal, entries);
        assertThat(entries.get(2).getAmount()).isEqualTo(new BigDecimal("0.3000"));
    }

    @Test
    void acceptsUpperAmountBoundaryWithoutRounding() {
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        assertThat(entry(journal, internal("CASH"), 1, EntrySide.DEBIT, "999999999999999.9999").getAmount())
                .isEqualTo(new BigDecimal("999999999999999.9999"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ZZZ", "US", "12X", "€UR"})
    void rejectsInvalidCurrencies(String code) {
        assertThatIllegalArgumentException().isThrownBy(() -> JournalTransaction.draft(code, null, AT));
        assertThatIllegalArgumentException().isThrownBy(
                () -> LedgerAccount.internal("CASH", LedgerAccountCategory.ASSET, code, AT));
    }

    @Test
    void rejectsMixedCurrencies() {
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        LedgerAccount usd = LedgerAccount.internal("CASH", LedgerAccountCategory.ASSET, "USD", AT);
        assertThatIllegalArgumentException().isThrownBy(() -> entry(journal, usd, 1, EntrySide.DEBIT, "1"));
    }

    @Test
    void customerLedgerAccountIsLiabilityAndUsesAccountCurrency() {
        Customer customer = Customer.create("Ana", "López", null, AT);
        Account account = Account.create(customer, AccountType.CHECKING, "USD", AT);
        LedgerAccount ledger = LedgerAccount.forAccount(account, AT);
        assertThat(ledger.getCategory()).isEqualTo(LedgerAccountCategory.LIABILITY);
        assertThat(ledger.getCurrencyCode()).isEqualTo("USD");
        assertThat(ledger.getAccount()).isSameAs(account);
        assertThat(ledger.getSystemCode()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerAccount.forAccount(null, AT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"cash", " HAS_SPACES ", "1CASH"})
    void requiresUnambiguousInternalAccountCode(String code) {
        assertThatIllegalArgumentException().isThrownBy(
                () -> LedgerAccount.internal(code, LedgerAccountCategory.ASSET, "MXN", AT));
    }

    @Test
    void requiresCategoryTimestampsAndValidOptionalReference() {
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerAccount.internal("CASH", null, "MXN", AT));
        assertThatIllegalArgumentException().isThrownBy(
                () -> LedgerAccount.internal("CASH", LedgerAccountCategory.ASSET, "MXN", null));
        assertThatIllegalArgumentException().isThrownBy(() -> JournalTransaction.draft("MXN", null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> JournalTransaction.draft("MXN", " ", AT));
        assertThatIllegalArgumentException().isThrownBy(() -> JournalTransaction.draft("MXN", "a".repeat(129), AT));
    }

    @Test
    void requiresJournalAccountSideAndPositiveLineNumber() {
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        LedgerAccount ledger = internal("CASH");
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerEntry.create(null, ledger, 1, EntrySide.DEBIT, BigDecimal.ONE));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerEntry.create(journal, null, 1, EntrySide.DEBIT, BigDecimal.ONE));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerEntry.create(journal, ledger, 1, null, BigDecimal.ONE));
        assertThatIllegalArgumentException().isThrownBy(() -> entry(journal, ledger, 0, EntrySide.DEBIT, "1"));
    }

    @Test
    void requiresAtLeastTwoLinesAndTwoDistinctAccounts() {
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        LedgerAccount ledger = internal("CASH");
        LedgerEntry debit = entry(journal, ledger, 1, EntrySide.DEBIT, "500");
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerPostingService.validate(journal, null));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerPostingService.validate(journal, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerPostingService.validate(journal, List.of(debit)));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerPostingService.validate(journal,
                List.of(debit, entry(journal, ledger, 2, EntrySide.CREDIT, "500"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBIT", "CREDIT"})
    void requiresBothSides(String side) {
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        EntrySide direction = EntrySide.valueOf(side);
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerPostingService.validate(journal, List.of(
                entry(journal, internal("A"), 1, direction, "500"),
                entry(journal, internal("B"), 2, direction, "500"))));
    }

    @Test
    void rejectsUnbalancedJournal() {
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerPostingService.validate(journal, List.of(
                entry(journal, internal("A"), 1, EntrySide.DEBIT, "500"),
                entry(journal, internal("B"), 2, EntrySide.CREDIT, "499.9999"))));
    }

    @Test
    void rejectsDuplicateLinesAndEntriesFromAnotherJournal() {
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        JournalTransaction other = JournalTransaction.draft("MXN", null, AT);
        LedgerEntry debit = entry(journal, internal("A"), 1, EntrySide.DEBIT, "500");
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerPostingService.validate(journal, List.of(
                debit, entry(journal, internal("B"), 1, EntrySide.CREDIT, "500"))));
        assertThatIllegalArgumentException().isThrownBy(() -> LedgerPostingService.validate(journal, List.of(
                debit, entry(other, internal("B"), 2, EntrySide.CREDIT, "500"))));
    }

    @Test
    void postingTimeIsDeterministicAndPostedJournalCannotBeReused() {
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        LedgerAccount a = internal("A");
        List<LedgerEntry> lines = List.of(entry(journal, a, 1, EntrySide.DEBIT, "1"),
                entry(journal, internal("B"), 2, EntrySide.CREDIT, "1"));
        assertThatIllegalArgumentException().isThrownBy(() -> journal.markPosted(AT.minusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> journal.markPosted(null));
        assertThat(journal.getStatus()).isEqualTo(JournalStatus.DRAFT);
        journal.markPosted(AT.plusSeconds(1));
        assertThat(journal.getPostedAt()).isEqualTo(AT.plusSeconds(1));
        assertThatIllegalStateException().isThrownBy(() -> journal.markPosted(AT.plusSeconds(2)));
        assertThatIllegalStateException().isThrownBy(() -> LedgerPostingService.validate(journal, lines));
        assertThatIllegalStateException().isThrownBy(() -> entry(journal, a, 3, EntrySide.DEBIT, "1"));
    }
}
