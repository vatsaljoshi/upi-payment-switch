package dev.vatsal.upiswitch;

import dev.vatsal.upiswitch.domain.Payment;
import dev.vatsal.upiswitch.domain.PaymentState;
import dev.vatsal.upiswitch.service.PaymentService;
import dev.vatsal.upiswitch.service.RecoverySweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sweeper-test;DB_CLOSE_DELAY=-1",
        "switch.sweeper.enabled=true",
        "switch.sweeper.stuck-after-seconds=-1",   // treat every in-flight payment as stuck
        "switch.sweeper.interval-millis=3600000"   // keep the scheduler out of the way
})
@Import(TestBankConfig.class)
class RecoverySweeperTest {

    @Autowired PaymentService service;
    @Autowired RecoverySweeper sweeper;
    @Autowired FakeBank bank;

    @Test
    void sweeperFinishesAPaymentLeftPendingByATimeout() {
        bank.reset();
        bank.creditTimeouts.set(1);

        Payment p = service.submit(UUID.randomUUID().toString(),
                "alice@switchbank", "bob@otherbank", new BigDecimal("12.00"));
        assertEquals(PaymentState.CREDIT_PENDING, p.getState());

        sweeper.sweep();

        assertEquals(PaymentState.SUCCESS, service.get(p.getId()).orElseThrow().getState());
        assertEquals(0, bank.reversals.get());
    }
}
