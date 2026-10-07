package io.github.alejandro117b.fincore.customer.api;

import java.net.URI;
import io.github.alejandro117b.fincore.api.ApiInputs;
import io.github.alejandro117b.fincore.customer.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/customers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Customers")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Unexpected technical failure.",
        content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(
                implementation = io.github.alejandro117b.fincore.api.ApiError.class)))
public class CustomerController {
    private final CustomerService service;

    public CustomerController(CustomerService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a customer", description = "Not idempotent: repeating this request can create another customer.")
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse result = service.create(request.firstName(), request.lastName(), request.email());
        return ResponseEntity.created(URI.create("/api/v1/customers/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a customer")
    public CustomerResponse get(@PathVariable String id) { return service.get(ApiInputs.uuid(id)); }
}
