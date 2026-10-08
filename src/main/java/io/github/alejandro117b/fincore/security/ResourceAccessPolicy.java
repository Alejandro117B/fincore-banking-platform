package io.github.alejandro117b.fincore.security;

import java.util.UUID;
import io.github.alejandro117b.fincore.account.AccountRepository;
import io.github.alejandro117b.fincore.api.ApiException;
import io.github.alejandro117b.fincore.auth.AuthRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ResourceAccessPolicy {
    private final AccountRepository accounts;

    public ResourceAccessPolicy(AccountRepository accounts) { this.accounts = accounts; }

    public void requireOwnCustomer(UUID id) {
        var actor = user();
        if (!id.equals(actor.customerId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer was not found.");
        }
    }

    @Transactional(readOnly = true)
    public void requireOwnAccount(UUID id) {
        var actor = user();
        if (!accounts.existsByIdAndCustomerId(id, actor.customerId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account was not found.");
        }
    }

    private CurrentActor user() {
        var actor = CurrentActor.require();
        if (!actor.roles().contains(AuthRole.USER)) { throw new AccessDeniedException("Access is forbidden"); }
        return actor;
    }
}
