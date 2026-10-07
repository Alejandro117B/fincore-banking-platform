package io.github.alejandro117b.fincore.ledger;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface LedgerAccountRepository extends Repository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findById(UUID id);

    Optional<LedgerAccount> findByAccountId(UUID accountId);
}
