package io.github.alejandro117b.fincore.account;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByCustomerId(UUID customerId);
}
