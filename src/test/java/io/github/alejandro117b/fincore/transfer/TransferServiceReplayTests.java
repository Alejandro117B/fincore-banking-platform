package io.github.alejandro117b.fincore.transfer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import io.github.alejandro117b.fincore.account.AccountRepository;
import io.github.alejandro117b.fincore.auth.AuthRole;
import io.github.alejandro117b.fincore.ledger.*;
import io.github.alejandro117b.fincore.security.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferServiceReplayTests {
    @Test
    void ownershipLostBetweenTheReplayGateAndProjectionReturnsSecurity404() {
        var manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        var accounts = mock(AccountRepository.class);
        var posting = mock(LedgerPostingService.class);
        var records = mock(TransferIdempotencyRepository.class);
        var transfers = mock(TransferRepository.class);
        var access = mock(ResourceAccessPolicy.class);
        var actor = new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), Set.of(AuthRole.USER));
        when(access.requireUser()).thenReturn(actor);
        var command = new TransferCommand(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "MXN", "ownership-race", null);
        String hash = TransferRequestHasher.hash(command.sourceAccountId(), command.destinationAccountId(), command.amount(), "MXN");
        when(records.reserve(eq(actor.userId()), eq(command.idempotencyKey()), eq(hash), any(Instant.class))).thenReturn(Optional.empty());
        when(records.findByRequesterAndKey(actor.userId(), command.idempotencyKey())).thenReturn(new TransferIdempotencyRepository.Reservation(
                UUID.randomUUID(), hash, 1, TransferIdempotencyStatus.SUCCEEDED, UUID.randomUUID(), null));
        doNothing().doNothing().doThrow(new io.github.alejandro117b.fincore.api.ApiException(
                org.springframework.http.HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account was not found."))
                .when(access).requireOwnAccount(command.sourceAccountId());
        var service = new TransferService(manager, accounts, mock(LedgerAccountRepository.class), mock(LedgerBalanceService.class),
                posting, transfers, records, mock(EntityManager.class), mock(JdbcTemplate.class), Clock.systemUTC(), access);
        assertThatThrownBy(() -> TestActors.call(actor, () -> service.execute(command)))
                .isInstanceOf(io.github.alejandro117b.fincore.api.ApiException.class).hasMessage("Account was not found.");
        verify(manager).rollback(status);
        verifyNoInteractions(posting, accounts);
        verify(records, never()).succeed(any(), any(), any(), any());
        verify(records, never()).reject(any(), any(), any(), any());
    }

    @Test
    void impossibleCommittedReservedRecordIsATechnicalFailureWithoutAttemptingMoney() {
        var manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        var accounts = mock(AccountRepository.class);
        var ledgers = mock(LedgerAccountRepository.class);
        var balances = mock(LedgerBalanceService.class);
        var posting = mock(LedgerPostingService.class);
        var transfers = mock(TransferRepository.class);
        var records = mock(TransferIdempotencyRepository.class);
        var access = mock(ResourceAccessPolicy.class);
        var actor = new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), Set.of(AuthRole.USER));
        when(access.requireUser()).thenReturn(actor);
        var command = new TransferCommand(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "MXN", "reserved", null);
        String hash = TransferRequestHasher.hash(command.sourceAccountId(), command.destinationAccountId(), command.amount(), "MXN");
        when(records.reserve(eq(actor.userId()), eq("reserved"), eq(hash), any(Instant.class))).thenReturn(Optional.empty());
        when(records.findByRequesterAndKey(actor.userId(), "reserved")).thenReturn(new TransferIdempotencyRepository.Reservation(
                UUID.randomUUID(), hash, 1, TransferIdempotencyStatus.RESERVED, null, null));
        var service = new TransferService(manager, accounts, ledgers, balances, posting, transfers, records,
                mock(EntityManager.class), mock(JdbcTemplate.class), Clock.systemUTC(), access);
        assertThatThrownBy(() -> TestActors.call(actor, () -> service.execute(command)))
                .isInstanceOf(IllegalStateException.class).hasMessage("A committed reservation must be terminal");
        verify(manager).rollback(status);
        verifyNoInteractions(accounts, ledgers, balances, posting, transfers);
        verify(records, never()).succeed(any(), any(), any(), any());
        verify(records, never()).reject(any(), any(), any(), any());
    }

    @Test
    void applicationBoundaryAlsoRejectsAdminWithoutUserBeforeOpeningATransaction() {
        var manager = mock(PlatformTransactionManager.class);
        var accounts = mock(AccountRepository.class);
        var actor = new CurrentActor(UUID.randomUUID(), null, Set.of(AuthRole.ADMIN));
        var service = new TransferService(manager, accounts, mock(LedgerAccountRepository.class),
                mock(LedgerBalanceService.class), mock(LedgerPostingService.class), mock(TransferRepository.class),
                mock(TransferIdempotencyRepository.class), mock(EntityManager.class), mock(JdbcTemplate.class),
                Clock.systemUTC(), new ResourceAccessPolicy(accounts));
        assertThatThrownBy(() -> TestActors.call(actor, () -> service.execute(
                new TransferCommand(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "MXN", "admin", null))))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(manager, accounts);
    }
}
