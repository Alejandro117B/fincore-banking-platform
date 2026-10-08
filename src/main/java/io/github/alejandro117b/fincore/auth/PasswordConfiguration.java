package io.github.alejandro117b.fincore.auth;

import java.util.Map;
import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password4j.Argon2Password4jPasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        var argon2 = new Argon2Password4jPasswordEncoder(Argon2Function.getInstance(19456, 2, 1, 32, Argon2.ID));
        return new DelegatingPasswordEncoder("argon2id", Map.of("argon2id", argon2));
    }
}
