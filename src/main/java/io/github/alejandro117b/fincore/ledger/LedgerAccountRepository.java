package io.github.alejandro117b.fincore.ledger;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.Repository;

public interface LedgerAccountRepository extends Repository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findById(UUID id);

    Optional<LedgerAccount> findByAccountId(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LedgerAccount a where a.id = :id")
    Optional<LedgerAccount> findByIdForUpdate(UUID id);
}
