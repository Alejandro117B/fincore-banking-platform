package io.github.alejandro117b.fincore.ledger;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface JournalTransactionRepository extends Repository<JournalTransaction, UUID> {

    Optional<JournalTransaction> findById(UUID id);
}
