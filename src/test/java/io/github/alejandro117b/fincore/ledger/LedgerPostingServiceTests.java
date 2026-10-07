package io.github.alejandro117b.fincore.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LedgerPostingServiceTests {

    private static final Instant AT = Instant.parse("2026-10-07T12:00:00Z");

    @Test
    void invalidProposalNeverWritesToPersistence() {
        EntityManager entityManager = mock(EntityManager.class);
        LedgerPostingService service = new LedgerPostingService(entityManager);
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        assertThatIllegalArgumentException().isThrownBy(() -> service.post(journal, List.of(), AT));
        verifyNoInteractions(entityManager);
        assertThat(journal.getStatus()).isEqualTo(JournalStatus.DRAFT);
    }

    @Test
    void flushesAllLinesWhileDraftBeforePosting() {
        EntityManager entityManager = mock(EntityManager.class);
        LedgerPostingService service = new LedgerPostingService(entityManager);
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        LedgerAccount a = LedgerAccount.internal("A", LedgerAccountCategory.ASSET, "MXN", AT);
        LedgerAccount b = LedgerAccount.internal("B", LedgerAccountCategory.ASSET, "MXN", AT);
        List<LedgerEntry> persistedLines = new ArrayList<>();
        List<JournalStatus> flushedStatuses = new ArrayList<>();
        doAnswer(invocation -> {
            if (invocation.getArgument(0) instanceof LedgerEntry entry) {
                persistedLines.add(entry);
            }
            return null;
        }).when(entityManager).persist(org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> {
            assertThat(persistedLines).hasSize(2);
            flushedStatuses.add(journal.getStatus());
            return null;
        }).when(entityManager).flush();
        service.post(journal, List.of(
                LedgerEntry.create(journal, a, 1, EntrySide.DEBIT, BigDecimal.ONE),
                LedgerEntry.create(journal, b, 2, EntrySide.CREDIT, BigDecimal.ONE)), AT);
        assertThat(flushedStatuses).containsExactly(JournalStatus.DRAFT, JournalStatus.POSTED);
    }
}
