package io.github.alejandro117b.fincore.api;

import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

final class StrictUuidDeserializer extends ValueDeserializer<UUID> {
    @Override
    public UUID deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return (UUID) context.handleUnexpectedToken(UUID.class, parser);
        }
        String value = parser.getString();
        try {
            return ApiInputs.uuid(value);
        } catch (ApiException exception) {
            throw context.weirdStringException(value, UUID.class, "A canonical UUID is required");
        }
    }
}
