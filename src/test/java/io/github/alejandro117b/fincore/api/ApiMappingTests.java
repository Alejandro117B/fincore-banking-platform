package io.github.alejandro117b.fincore.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import io.github.alejandro117b.fincore.account.Account;
import io.github.alejandro117b.fincore.account.AccountType;
import io.github.alejandro117b.fincore.account.api.AccountApiMapper;
import io.github.alejandro117b.fincore.customer.Customer;
import io.github.alejandro117b.fincore.customer.api.CustomerApiMapper;
import io.github.alejandro117b.fincore.transfer.TransferErrorCode;
import io.github.alejandro117b.fincore.transfer.TransferResult;
import io.github.alejandro117b.fincore.transfer.api.CreateTransferRequest;
import io.github.alejandro117b.fincore.transfer.api.TransferApiMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class ApiMappingTests {
    private static final Instant AT = Instant.parse("2026-10-07T12:00:00.123456Z");

    @Test
    void customerAndClosedAccountMappingKeepOnlyThePublicContract() {
        Customer customer = Customer.create(" Ana ", " Perez ", null, AT);
        var customerDto = CustomerApiMapper.from(customer);
        assertThat(customerDto.firstName()).isEqualTo("Ana");
        assertThat(customerDto.email()).isNull();
        assertThat(customerDto.createdAt()).isEqualTo(AT);
        Account account = Account.create(customer, AccountType.CHECKING, "mxn", AT);
        account.close(AT.plusSeconds(1));
        var accountDto = AccountApiMapper.from(account);
        assertThat(accountDto.customerId()).isEqualTo(customer.getId());
        assertThat(accountDto.currencyCode()).isEqualTo("MXN");
        assertThat(accountDto.closedAt()).isEqualTo(AT.plusSeconds(1));
        assertThat(accountDto.updatedAt()).isEqualTo(accountDto.closedAt());
        assertThat(customerDto.getClass().getRecordComponents()).extracting(component -> component.getName())
                .doesNotContain("version", "accounts");
        assertThat(accountDto.getClass().getRecordComponents()).extracting(component -> component.getName())
                .doesNotContain("version", "balance", "customer", "ledgerAccount");
    }

    @Test
    void transferMappingPreservesExactPrecisionBeyondJavascriptSafeIntegerAndOptionalFields() {
        UUID source = UUID.randomUUID();
        UUID destination = UUID.randomUUID();
        var request = new CreateTransferRequest(source, destination, "999999999999999.99", "MXN", null);
        var command = TransferApiMapper.command(request, "case-SENSITIVE");
        assertThat(command.amount()).isEqualTo(new BigDecimal("999999999999999.99"));
        assertThat(command.idempotencyKey()).isEqualTo("case-SENSITIVE");
        TransferResult result = new TransferResult(UUID.randomUUID(), source, destination, command.amount(),
                "MXN", null, UUID.randomUUID(), AT, AT.plusSeconds(1));
        var dto = TransferApiMapper.from(result);
        assertThat(dto.amount()).isEqualTo("999999999999999.9900");
        assertThat(dto.reference()).isNull();
        assertThat(dto.journalTransactionId()).isEqualTo(result.journalTransactionId());
        assertThat(dto.completedAt()).isEqualTo(AT.plusSeconds(1));
        assertThat(ApiInputs.money(new BigDecimal("-0.0001"))).isEqualTo("-0.0001");
        assertThatThrownBy(() -> ApiInputs.money(new BigDecimal("0.00001"))).isInstanceOf(ArithmeticException.class);
    }

    @ParameterizedTest
    @MethodSource("errors")
    void transferErrorsHaveExplicitHttpMappings(TransferErrorCode code, int status, String publicCode) {
        var error = TransferHttpErrors.map(code);
        assertThat(error.getStatus().value()).isEqualTo(status);
        assertThat(error.getCode()).isEqualTo(publicCode);
        assertThat(error.getMessage()).isNotBlank();
    }

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(TransferErrorCode.INVALID_REQUEST, 400, "INVALID_REQUEST"),
                Arguments.of(TransferErrorCode.INVALID_AMOUNT, 422, "INVALID_AMOUNT"),
                Arguments.of(TransferErrorCode.INVALID_CURRENCY, 422, "INVALID_CURRENCY"),
                Arguments.of(TransferErrorCode.ACCOUNT_NOT_FOUND, 404, "ACCOUNT_NOT_FOUND"),
                Arguments.of(TransferErrorCode.LEDGER_ACCOUNT_NOT_FOUND, 409, "ACCOUNT_NOT_READY"),
                Arguments.of(TransferErrorCode.INSUFFICIENT_FUNDS, 409, "INSUFFICIENT_FUNDS"),
                Arguments.of(TransferErrorCode.ACCOUNT_BLOCKED, 409, "ACCOUNT_BLOCKED"),
                Arguments.of(TransferErrorCode.ACCOUNT_CLOSED, 409, "ACCOUNT_CLOSED"),
                Arguments.of(TransferErrorCode.CURRENCY_MISMATCH, 422, "CURRENCY_MISMATCH"),
                Arguments.of(TransferErrorCode.SAME_ACCOUNT, 422, "SAME_ACCOUNT"),
                Arguments.of(TransferErrorCode.IDEMPOTENCY_CONFLICT, 409, "IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void cursorContractIsVersionedAccountBoundAndRejectsUnsafeBounds() {
        UUID account = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var cursor = new io.github.alejandro117b.fincore.account.api.AccountTransactionsCursor(account, AT, id, AT, id);
        assertThat(io.github.alejandro117b.fincore.account.api.AccountTransactionsCursor.decode(cursor.encode(), account)).isEqualTo(cursor);
        assertThatThrownBy(() -> io.github.alejandro117b.fincore.account.api.AccountTransactionsCursor.decode(cursor.encode(), UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
        String decoded = new String(java.util.Base64.getUrlDecoder().decode(cursor.encode()), java.nio.charset.StandardCharsets.UTF_8);
        String futureVersion = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                decoded.replaceFirst("1\\|", "2|").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> io.github.alejandro117b.fincore.account.api.AccountTransactionsCursor.decode(futureVersion, account))
                .isInstanceOf(ApiException.class);
        var invalid = new io.github.alejandro117b.fincore.account.api.AccountTransactionsCursor(account, AT, id, AT.plusSeconds(1), id);
        assertThatThrownBy(() -> io.github.alejandro117b.fincore.account.api.AccountTransactionsCursor.decode(invalid.encode(), account))
                .isInstanceOf(ApiException.class);
    }
}
