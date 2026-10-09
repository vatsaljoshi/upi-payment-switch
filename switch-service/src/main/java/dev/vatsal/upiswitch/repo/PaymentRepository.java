package dev.vatsal.upiswitch.repo;

import dev.vatsal.upiswitch.domain.Payment;
import dev.vatsal.upiswitch.domain.PaymentState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    /** Oldest in-flight payments that haven't moved since the cutoff, in bounded batches. */
    List<Payment> findTop100ByStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<PaymentState> states, Instant cutoff);
}
