package io.github.alejandro117b.fincore.account.api;

import io.github.alejandro117b.fincore.account.Account;

public final class AccountApiMapper {
    private AccountApiMapper() {
    }

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getCustomer().getId(), account.getType(),
                account.getStatus(), account.getCurrencyCode(), account.getCreatedAt(),
                account.getUpdatedAt(), account.getClosedAt());
    }
}
