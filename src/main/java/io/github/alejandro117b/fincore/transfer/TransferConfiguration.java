package io.github.alejandro117b.fincore.transfer;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TransferConfiguration {
    @Bean
    Clock transferClock() {
        return Clock.systemUTC();
    }
}
