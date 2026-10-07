package io.github.alejandro117b.fincore.api;

import java.util.UUID;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer strictApiJson() {
        return builder -> {
            builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
            builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            builder.withCoercionConfig(LogicalType.Textual, config -> {
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            });
            builder.withCoercionConfig(LogicalType.Enum, config -> {
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            });
            builder.addModule(new SimpleModule("strict-api-uuid")
                    .addDeserializer(UUID.class, new StrictUuidDeserializer()));
        };
    }
}
