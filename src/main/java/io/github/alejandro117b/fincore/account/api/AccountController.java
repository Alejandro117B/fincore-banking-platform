package io.github.alejandro117b.fincore.account.api;

import java.net.URI;
import io.github.alejandro117b.fincore.account.AccountService;
import io.github.alejandro117b.fincore.account.AccountStatementQueryService;
import io.github.alejandro117b.fincore.api.ApiInputs;
import io.github.alejandro117b.fincore.security.ResourceAccessPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Accounts")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected technical failure.",
        content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(
                implementation = io.github.alejandro117b.fincore.api.ApiError.class)))
public class AccountController {
    private final AccountService service;
    private final AccountStatementQueryService statements;
    private final ResourceAccessPolicy access;

    public AccountController(AccountService service, AccountStatementQueryService statements,
                             ResourceAccessPolicy access) {
        this.service = service;
        this.statements = statements;
        this.access = access;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account", description = "Atomically creates its LIABILITY ledger account, without funds or entries. Not idempotent. ACTIVE does not imply KYC or onboarding.")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse result = service.create(request.customerId(), request.type(), request.currencyCode());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an account")
    public AccountResponse get(@PathVariable String id) {
        var accountId = ApiInputs.uuid(id);
        access.requireOwnAccount(accountId);
        return service.get(accountId);
    }

    @GetMapping("/{id}/balance")
    @Operation(summary = "Get posted balance", description = "Ledger is the source of truth. POSTED only; not available balance. Existing account without a ledger account returns 409 ACCOUNT_NOT_READY.")
    public AccountBalanceResponse balance(@PathVariable String id) {
        var accountId = ApiInputs.uuid(id);
        access.requireOwnAccount(accountId);
        return service.balance(accountId);
    }

    @GetMapping("/{id}/transactions")
    @Operation(summary = "Get account journal participation", description = "One aggregated row per POSTED journal. Keyset ordered by postedAt DESC, journalTransactionId DESC. Cursor is versioned and account-bound; not a frozen snapshot between pages. No OFFSET or total count.")
    public AccountTransactionsResponse transactions(@PathVariable String id,
            @Parameter(description = "Page size: default 20, minimum 1, maximum 100.")
            @RequestParam(defaultValue = "20") String limit,
            @Parameter(description = "Opaque nextCursor from the previous page for this account.")
            @RequestParam(required = false) String cursor) {
        var accountId = ApiInputs.uuid(id);
        access.requireOwnAccount(accountId);
        return statements.get(accountId, limit, cursor);
    }
}
