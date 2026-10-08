package io.github.alejandro117b.fincore.api;

import java.util.Comparator;
import java.util.List;
import io.github.alejandro117b.fincore.transfer.TransferException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiErrorResponses errors;

    public ApiExceptionHandler(ApiErrorResponses errors) { this.errors = errors; }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> authentication(AuthenticationException exception, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        boolean missing = exception instanceof org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
        if (missing) { headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer"); }
        return response(HttpStatus.UNAUTHORIZED, missing ? "AUTHENTICATION_REQUIRED" : "INVALID_CREDENTIALS",
                missing ? "Authentication is required." : "Credentials are invalid.", List.of(), request, headers);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden(AccessDeniedException exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is forbidden.", List.of(), request, new HttpHeaders());
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> controlled(ApiException exception, HttpServletRequest request) {
        return response(exception.getStatus(), exception.getCode(), exception.getMessage(),
                List.of(), request, new HttpHeaders());
    }

    @ExceptionHandler(TransferException.class)
    ResponseEntity<Object> transfer(TransferException exception, HttpServletRequest request) {
        return controlled(TransferHttpErrors.map(exception.getCode()), request);
    }

    @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class})
    ResponseEntity<Object> unavailable(Exception exception, HttpServletRequest request) {
        LOG.error("Temporary API failure requestId={}", RequestIdFilter.id(request), exception);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The operation could not be completed. Retry with the same idempotency key if applicable.",
                List.of(), request, new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Unexpected API failure requestId={}", RequestIdFilter.id(request), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The operation could not be completed.", List.of(), request, new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest webRequest) {
        HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
        List<ApiValidationError> details = List.of();
        String code = switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "INTERNAL_ERROR";
        };
        String message = switch (status.value()) {
            case 400 -> "Request format or parameters are invalid.";
            case 404 -> "The requested resource was not found.";
            case 405 -> "This HTTP method is not supported.";
            case 406 -> "The requested response format is not supported.";
            case 415 -> "Content-Type must be application/json.";
            default -> "The operation could not be completed.";
        };
        if (exception instanceof HttpMessageNotReadableException) {
            code = "MALFORMED_REQUEST";
            message = "Request body must match the documented JSON contract.";
        } else if (exception instanceof MethodArgumentNotValidException validation) {
            code = "VALIDATION_ERROR";
            message = "Request fields are invalid.";
            details = validation.getBindingResult().getFieldErrors().stream()
                    .map(error -> new ApiValidationError(error.getField(), safeValidationCode(error.getCode()),
                            "Field does not match the documented format or limits."))
                    .distinct().sorted(Comparator.comparing(ApiValidationError::field).thenComparing(ApiValidationError::code))
                    .toList();
        }
        if (status.is5xxServerError()) {
            LOG.error("Framework API failure requestId={}", RequestIdFilter.id(request), exception);
        }
        return response(status, code, message, details, request, headers);
    }

    private String safeValidationCode(String code) {
        return code == null ? "INVALID" : switch (code) {
            case "NotNull", "NotBlank" -> "REQUIRED";
            case "Size" -> "INVALID_LENGTH";
            case "Email", "Pattern" -> "INVALID_FORMAT";
            default -> "INVALID";
        };
    }

    private ResponseEntity<Object> response(HttpStatusCode status, String code, String message,
                                           List<ApiValidationError> details, HttpServletRequest request, HttpHeaders original) {
        return errors.response(status, code, message, details, request, original);
    }
}
