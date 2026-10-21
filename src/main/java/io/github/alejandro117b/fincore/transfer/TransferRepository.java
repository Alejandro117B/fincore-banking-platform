package io.github.alejandro117b.fincore.transfer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;

public interface TransferRepository extends Repository<Transfer, UUID> {
    Optional<Transfer> findById(UUID id);

    @Query("""
            select new io.github.alejandro117b.fincore.transfer.TransferResult(
                t.id, s.id, d.id, t.amount, t.currencyCode, t.reference,
                t.journalTransaction.id, t.createdAt, t.completedAt)
            from Transfer t join t.sourceAccount s join t.destinationAccount d
            where t.id = :id and (s.customer.id = :customerId or d.customer.id = :customerId)
            """)
    Optional<TransferResult> findVisibleResult(UUID id, UUID customerId);

    // POST replay is restricted to the original requester and current source owner, never a recipient.
    @Query("""
            select new io.github.alejandro117b.fincore.transfer.TransferResult(
                t.id, s.id, d.id, t.amount, t.currencyCode, t.reference,
                t.journalTransaction.id, t.createdAt, t.completedAt)
            from TransferIdempotencyRecord r join r.transfer t
                join t.sourceAccount s join t.destinationAccount d
            where r.id = :reservationId and r.requesterUserId = :requester
                and r.requestHash = :hash and r.hashVersion = :hashVersion
                and r.status = io.github.alejandro117b.fincore.transfer.TransferIdempotencyStatus.SUCCEEDED
                and s.customer.id = :customerId
            """)
    Optional<TransferResult> findReplayResult(UUID reservationId, UUID requester, String hash,
                                             int hashVersion, UUID customerId);
}
