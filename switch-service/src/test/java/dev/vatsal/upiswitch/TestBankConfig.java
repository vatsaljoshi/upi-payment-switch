package dev.vatsal.upiswitch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Swaps the HTTP bank client for the in-memory FakeBank. */
@TestConfiguration
public class TestBankConfig {

    @Bean
    @Primary
    FakeBank fakeBank() {
        return new FakeBank();
    }
}
