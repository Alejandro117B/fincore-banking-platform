package io.github.alejandro117b.fincore.api;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import io.github.alejandro117b.fincore.account.AccountService;
import io.github.alejandro117b.fincore.account.AccountStatementQueryService;
import io.github.alejandro117b.fincore.account.AccountStatus;
import io.github.alejandro117b.fincore.account.AccountType;
import io.github.alejandro117b.fincore.account.api.*;
import io.github.alejandro117b.fincore.customer.CustomerService;
import io.github.alejandro117b.fincore.customer.api.*;
import io.github.alejandro117b.fincore.transfer.*;
import io.github.alejandro117b.fincore.transfer.api.TransferController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({CustomerController.class, AccountController.class, TransferController.class})
@Import({JsonConfiguration.class, ApiExceptionHandler.class, RequestIdFilter.class})
class ApiMvcTests {
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant AT = Instant.parse("2026-10-07T12:00:00Z");
    private static final String TRANSFER = """
            {"sourceAccountId":"%s","destinationAccountId":"%s","amount":"500.00","currencyCode":"MXN"}
            """.formatted(SOURCE, DESTINATION);
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @MockitoBean private CustomerService customers;
    @MockitoBean private AccountService accounts;
    @MockitoBean private AccountStatementQueryService statements;
    @MockitoBean private TransferService transfers;
    @MockitoBean private TransferQueryService queries;
    @MockitoBean private Clock clock;

    @BeforeEach
    void deterministicClock() { when(clock.instant()).thenReturn(AT); }

    @Test
    void transferUsesHeaderAndExactCommandAndReturnsLocationAndCorrelation() throws Exception {
        UUID id = UUID.randomUUID();
        UUID journal = UUID.randomUUID();
        when(transfers.execute(any())).thenReturn(new TransferResult(id, SOURCE, DESTINATION,
                new BigDecimal("500.0000"), "MXN", null, journal, AT, AT));
        var response = mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "Case-Key").header("X-Request-Id", "untrusted-client-id").content(TRANSFER))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/transfers/" + id))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.amount").isString()).andExpect(jsonPath("$.amount").value("500.0000"))
                .andExpect(jsonPath("$.reference").doesNotExist()).andExpect(jsonPath("$.requestHash").doesNotExist())
                .andReturn().getResponse();
        assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}").isNotEqualTo("untrusted-client-id");
        verify(transfers).execute(new TransferCommand(SOURCE, DESTINATION, new BigDecimal("500.00"), "MXN", "Case-Key", null));
    }

    @Test
    void customerAndAccountControllersUseOnlyTheirPublicDtos() throws Exception {
        when(customers.create("Ana", "Perez", null)).thenReturn(new CustomerResponse(SOURCE, "Ana", "Perez", null, AT, AT));
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Ana\",\"lastName\":\"Perez\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/customers/" + SOURCE))
                .andExpect(jsonPath("$.version").doesNotExist()).andExpect(jsonPath("$.accounts").doesNotExist());
        when(accounts.create(SOURCE, AccountType.CHECKING, "MXN")).thenReturn(new AccountResponse(DESTINATION, SOURCE,
                AccountType.CHECKING, AccountStatus.ACTIVE, "MXN", AT, AT, null));
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + SOURCE + "\",\"type\":\"CHECKING\",\"currencyCode\":\"MXN\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.customerId").value(SOURCE.toString()))
                .andExpect(jsonPath("$.balance").doesNotExist()).andExpect(jsonPath("$.ledgerAccount").doesNotExist());
    }

    @ParameterizedTest
    @MethodSource("malformedBodies")
    void strictJsonRejectsUnknownDuplicateCoercedAndMalformedTransferFields(String body) throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "key").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(transfers);
    }

    static Stream<String> malformedBodies() {
        return Stream.of(TRANSFER.replace("\"500.00\"", "500.00"), TRANSFER.replace("\"500.00\"", "500"),
                TRANSFER.replace("\"500.00\"", "true"), TRANSFER.replace("\"500.00\"", "[]"),
                TRANSFER.replace("\"MXN\"", "123"), TRANSFER.replace("\"amount\":", "\"amount\":\"1\",\"amount\":"),
                TRANSFER.replace("\"currencyCode\":", "\"balance\":\"1000\",\"currencyCode\":"),
                TRANSFER.replace("\"currencyCode\":", "\"requestHash\":\"fake\",\"currencyCode\":"),
                TRANSFER.replace("\"currencyCode\":", "\"ledgerEntries\":[],\"currencyCode\":"),
                TRANSFER.replace(SOURCE.toString(), "1-1-1-1-1"), TRANSFER.replace(SOURCE.toString(), "not-a-uuid"),
                TRANSFER.replace(SOURCE.toString(), "AAAAAAAAAAAAAAAAAAAAAQ=="), "{", TRANSFER + "{}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"5e2", "500,00", "1,000.00", "+500", "0500", "", " 500 "})
    void plainDecimalContractIsValidatedBeforeTheService(String amount) throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "key")
                        .content(TRANSFER.replace("500.00", amount)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("amount"));
        verifyNoInteractions(transfers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "with space", "comma,key", "slash/key"})
    void invalidKeysCannotReachTheApplication(String key) throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key).content(TRANSFER))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(transfers);
    }

    @Test
    void missingDuplicatedAndOversizedHeadersAreRejected() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(TRANSFER)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "one", "two").content(TRANSFER))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "k".repeat(129)).content(TRANSFER))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(transfers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "1", "1.5"})
    void unknownAndNumericEnumsAreNotCoerced(String type) throws Exception {
        String token = type.equals("UNKNOWN") ? "\"UNKNOWN\"" : type;
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + SOURCE + "\",\"type\":" + token + ",\"currencyCode\":\"MXN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(accounts);
    }

    @Test
    void requestValidationAndUnknownStatusAreSafe() throws Exception {
        mvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\" \",\"lastName\":\"Perez\",\"email\":\"secret-invalid-email\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + SOURCE + "\",\"type\":\"CHECKING\",\"currencyCode\":\"MXN\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(customers, accounts);
    }

    @Test
    void businessErrorsHaveCorrelationAndNoRejectedRequestValues() throws Exception {
        when(transfers.execute(any())).thenThrow(new TransferException(TransferErrorCode.INSUFFICIENT_FUNDS));
        var response = mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "secret-key").content(TRANSFER))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.status").value(409)).andReturn().getResponse();
        var error = json.readTree(response.getContentAsString());
        assertThat(error.path("requestId").asText()).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(response.getContentAsString()).doesNotContain("secret-key", "stackTrace", "exception", "PostgreSQL");
    }

    @Test
    void unexpectedIllegalArgumentAndSqlExceptionsRemainSafeTechnicalErrors() throws Exception {
        when(customers.get(SOURCE)).thenThrow(new IllegalArgumentException("internal bug password=secret"));
        mvc.perform(get("/api/v1/customers/" + SOURCE)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
        when(transfers.execute(any())).thenThrow(new DataIntegrityViolationException("SQLSTATE 23514 constraint ck_secret Hibernate PostgreSQL"));
        var response = mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "key").content(TRANSFER))
                .andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("SQLSTATE", "23514", "ck_secret", "Hibernate", "PostgreSQL");
        doThrow(new CannotAcquireLockException("deadlock diagnostic")).when(transfers).execute(any());
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "key").content(TRANSFER))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void invalidPathUuidDoesNotGetEchoedAndMissingResourceIs404() throws Exception {
        mvc.perform(get("/api/v1/accounts/invalid-private-input")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UUID")).andExpect(jsonPath("$.path").value("/api/v1/accounts/{id}"));
        when(accounts.get(SOURCE)).thenThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account was not found."));
        mvc.perform(get("/api/v1/accounts/" + SOURCE)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void methodMediaTypeAcceptAndUnknownRoutesHaveUniformErrors() throws Exception {
        mvc.perform(put("/api/v1/customers/" + SOURCE)).andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow")).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/customers").contentType(MediaType.TEXT_PLAIN).content("not json"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/api/v1/accounts/" + SOURCE).accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
        mvc.perform(get("/api/v1/missing-route")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
