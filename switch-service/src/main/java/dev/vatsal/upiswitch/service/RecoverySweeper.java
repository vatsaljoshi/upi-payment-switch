package dev.vatsal.upiswitch.service;

import dev.vatsal.upiswitch.domain.Payment;
import dev.vatsal.upiswitch.domain.PaymentState;
import dev.vatsal.upiswitch.repo.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Finishes payments that got stuck in flight: the switch crashed mid-saga, or a bank
 * never gave a definitive answer. It simply resumes the saga from the saved state,
 * so recovery uses exactly the same code path as a normal payment.
 */
@Component
@ConditionalOnProperty(prefix = "switch.sweeper", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RecoverySweeper {

    private static final Logger log = LoggerFactory.getLogger(RecoverySweeper.class);

    private static final List<PaymentState> IN_FLIGHT = Arrays.stream(PaymentState.values())
            .filter(s -> !s.isTerminal())
            .toList();

    private final PaymentRepository payments;
    private final PaymentService service;
    private final long stuckAfterSeconds;

    public RecoverySweeper(PaymentRepository payments,
                           PaymentService service,
                           @Value("${switch.sweeper.stuck-after-seconds:15}") long stuckAfterSeconds) {
        this.payments = payments;
        this.service = service;
        this.stuckAfterSeconds = stuckAfterSeconds;
    }

    @Scheduled(fixedDelayString = "${switch.sweeper.interval-millis:5000}")
    public void sweep() {
        Instant cutoff = Instant.now().minusSeconds(stuckAfterSeconds);
        List<Payment> stuck = payments.findTop100ByStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(IN_FLIGHT, cutoff);
        if (stuck.isEmpty()) {
            return;
        }
        log.info("Recovery sweeper picked up {} in-flight payment(s)", stuck.size());
        for (Payment p : stuck) {
            PaymentState before = p.getState();
            try {
                Payment after = service.advance(p);
                log.info("Recovered txn {}: {} -> {}", p.getId(), before, after.getState());
            } catch (ObjectOptimisticLockingFailureException e) {
                log.info("Txn {} was advanced by another worker; skipping", p.getId());
            } catch (RuntimeException e) {
                log.error("Recovery of txn {} failed: {}", p.getId(), e.getMessage());
            }
        }
    }
}
