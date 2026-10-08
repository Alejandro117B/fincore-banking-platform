package io.github.alejandro117b.fincore.api;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/** One error contract for MVC and security filters, including safe path and correlation. */
@Component
public class ApiErrorResponses {
    private final Clock clock;
    private final JsonMapper json;

    public ApiErrorResponses(Clock clock, JsonMapper json) { this.clock = clock; this.json = json; }

    public ResponseEntity<Object> response(HttpStatusCode status, String code, String message,
            List<ApiValidationError> details, HttpServletRequest request, HttpHeaders original) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(original);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", RequestIdFilter.id(request));
        headers.setCacheControl("no-store");
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = route == null ? "/api/v1" : route.toString();
        ApiError error = new ApiError(clock.instant(), status.value(), code, message, path,
                RequestIdFilter.id(request), details);
        return new ResponseEntity<>(error, headers, status);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                      String code, String message, boolean bearerChallenge) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        if (bearerChallenge) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "INVALID_TOKEN".equals(code)
                    ? "Bearer error=\"invalid_token\"" : "Bearer");
        }
        var entity = response(status, code, message, List.of(), request, headers);
        response.setStatus(status.value());
        entity.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.getWriter().write(json.writeValueAsString(entity.getBody()));
    }
}
