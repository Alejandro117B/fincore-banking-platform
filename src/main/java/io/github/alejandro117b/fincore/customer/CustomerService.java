package io.github.alejandro117b.fincore.customer;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import io.github.alejandro117b.fincore.api.ApiException;
import io.github.alejandro117b.fincore.customer.api.CustomerApiMapper;
import io.github.alejandro117b.fincore.customer.api.CustomerResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {
    private final CustomerRepository customers;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, Clock clock) {
        this.customers = customers;
        this.clock = clock;
    }

    @Transactional
    public CustomerResponse create(String firstName, String lastName, String email) {
        var at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Customer customer;
        try {
            customer = Customer.create(firstName, lastName, email, at);
        } catch (IllegalArgumentException exception) {
            // Only this known input factory rejection is translated, never persistence failures.
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_CUSTOMER_DETAILS", "Customer details are invalid.");
        }
        return CustomerApiMapper.from(customers.saveAndFlush(customer));
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(UUID id) {
        return CustomerApiMapper.from(customers.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer was not found.")));
    }
}
