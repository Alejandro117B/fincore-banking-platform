package io.github.alejandro117b.fincore.transfer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface TransferRepository extends Repository<Transfer, UUID> {
    Optional<Transfer> findById(UUID id);
}
