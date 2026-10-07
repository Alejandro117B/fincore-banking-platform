package io.github.alejandro117b.fincore.transfer.api;

import java.net.URI;
import java.util.Collections;
import io.github.alejandro117b.fincore.api.ApiException;
import io.github.alejandro117b.fincore.api.ApiInputs;
import io.github.alejandro117b.fincore.transfer.TransferQueryService;
import io.github.alejandro117b.fincore.transfer.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Transfers")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected technical failure.",
        content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(
                implementation = io.github.alejandro117b.fincore.api.ApiError.class)))
public class TransferController {
    private final TransferService service;
    private final TransferQueryService queries;

    public TransferController(TransferService service, TransferQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    // Deliberately no @Transactional: TransferService commits rejections before throwing.
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Execute an internal transfer", description = "Original and successful replay both return 201, identical body and Location. Same key and different economic request: 409. Reference is excluded from the hash; replays preserve the original reference. Expected rejections are committed and replayed; technical transaction failures roll back. A lost HTTP response may follow a successful commit: retry with the same key. MXN/USD: 2 decimals; JPY: 0.")
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody CreateTransferRequest request,
            @Parameter(required = true, description = "Exactly one case-sensitive value; 1–128 characters. Required only for this endpoint.",
                    schema = @Schema(type = "string", minLength = 1, maxLength = 128, pattern = "[A-Za-z0-9._:-]+"))
            @RequestHeader("Idempotency-Key") String ignoredCombinedKey, HttpServletRequest servletRequest) {
        var keys = Collections.list(servletRequest.getHeaders("Idempotency-Key"));
        if (keys.size() != 1 || !keys.getFirst().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Exactly one valid Idempotency-Key is required.");
        }
        TransferResponse result = TransferApiMapper.from(service.execute(TransferApiMapper.command(request, keys.getFirst())));
        return ResponseEntity.created(URI.create("/api/v1/transfers/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a completed transfer", description = "Rejected requests do not create a Transfer resource.")
    public TransferResponse get(@PathVariable String id) {
        return TransferApiMapper.from(queries.get(ApiInputs.uuid(id)));
    }
}
