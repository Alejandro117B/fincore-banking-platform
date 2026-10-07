package io.github.alejandro117b.fincore.customer.api;

import io.github.alejandro117b.fincore.customer.Customer;

public final class CustomerApiMapper {
    private CustomerApiMapper() {
    }

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getFirstName(), customer.getLastName(),
                customer.getEmail(), customer.getCreatedAt(), customer.getUpdatedAt());
    }
}
