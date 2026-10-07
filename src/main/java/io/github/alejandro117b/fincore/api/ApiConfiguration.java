package io.github.alejandro117b.fincore.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApiConfiguration {
    @Bean
    OpenAPI finCoreApi() {
        Components components = new Components();
        return new OpenAPI().components(components).info(new Info().title("FinCore development API").version("v1")
                .description("Local development interface. No authentication or ownership authorization: not safe for public exposure. Money uses JSON strings, never numeric tokens. Customer/Account creation is not idempotent."));
    }

    @Bean
    OpenApiCustomizer publicErrorContracts() {
        return api -> api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            addError(operation.getResponses(), "400", "Invalid request, JSON, UUID, header or pagination.");
            addError(operation.getResponses(), "500", "Unexpected technical failure; safe diagnostic only.");
            addError(operation.getResponses(), "503", "Temporary technical failure; retry with the same transfer key.");
            if (method.name().equals("GET") || path.equals("/api/v1/accounts") || path.equals("/api/v1/transfers")) {
                addError(operation.getResponses(), "404", "Requested resource not found.");
            }
            if (method.name().equals("POST")) {
                addError(operation.getResponses(), "415", "Content-Type must be application/json.");
                addError(operation.getResponses(), "422", "Known input/domain rejection.");
            }
            if (path.equals("/api/v1/transfers") || path.endsWith("/balance") || path.endsWith("/transactions")) {
                addError(operation.getResponses(), "409", "Business conflict, account not ready or idempotency conflict.");
            }
            operation.getResponses().values().forEach(response -> {
                response.addHeaderObject("X-Request-Id", new Header().description("Server-generated request correlation UUID.")
                        .schema(new StringSchema().format("uuid")));
                response.addHeaderObject("Cache-Control", new Header().schema(new StringSchema().example("no-store")));
            });
            if (operation.getResponses().containsKey("201")) {
                operation.getResponses().get("201").addHeaderObject("Location", new Header()
                        .description("Resource URI; successful transfer replays preserve the original Location.")
                        .schema(new StringSchema().example(path + "/00000000-0000-0000-0000-000000000001")));
            }
        }));
    }

    private void addError(io.swagger.v3.oas.models.responses.ApiResponses responses, String status, String description) {
        responses.addApiResponse(status, new ApiResponse().description(description).content(new Content()
                .addMediaType("application/json", new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")))));
    }
}
