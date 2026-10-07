package io.github.alejandro117b.fincore.customer;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
}
