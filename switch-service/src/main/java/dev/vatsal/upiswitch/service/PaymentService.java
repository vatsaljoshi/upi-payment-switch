package dev.vatsal.upiswitch.service;

import dev.vatsal.upiswitch.domain.IdempotencyRecord;
import dev.vatsal.upiswitch.domain.Payment;
import dev.vatsal.upiswitch.domain.PaymentState;
import dev.vatsal.upiswitch.repo.IdempotencyRepository;
import dev.vatsal.upiswitch.repo.PaymentRepository;
import dev.vatsal.upiswitch.service.BankClient.BankResponse;
import dev.vatsal.upiswitch.service.BankClient.BankTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import static dev.vatsal.upiswitch.domain.PaymentState.CREDIT_FAILED;
import static dev.vatsal.upiswitch.domain.PaymentState.CREDIT_PENDING;
import static dev.vatsal.upiswitch.domain.PaymentState.DEBITED;
import static dev.vatsal.upiswitch.domain.PaymentState.DEBIT_PENDING;
import static dev.vatsal.upiswitch.domain.PaymentState.FAILED;
import static dev.vatsal.upiswitch.domain.PaymentState.REVERSAL_PENDING;
import static dev.vatsal.upiswitch.domain.PaymentState.REVERSED;
import static dev.vatsal.upiswitch.domain.PaymentState.SUCCESS;

/**
 * Runs a payment as a two-leg saga: debit the payer, credit the payee, and reverse the
 * debit if the payee bank definitively declines.
 *
 * Two rules keep money safe:
 *  1. Every state change is committed BEFORE the next bank call. If the process dies
 *     mid-payment, the database still says exactly how far it got.
 *  2. An unknown bank outcome is never treated as a failure. The payment waits in its
 *     *_PENDING state and the recovery sweeper re-sends the same leg later.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final IdempotencyRepository idempotency;
    private final BankClient bank;
    private final TransactionTemplate tx;

    public PaymentService(PaymentRepository payments,
                          IdempotencyRepository idempotency,
                          BankClient bank,
                          PlatformTransactionManager transactionManager) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.bank = bank;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Idempotent entry point: the same key always maps to the same payment.
     */
    public Payment submit(String idempotencyKey, String payerVpa, String payeeVpa, BigDecimal amount) {
        if (payerVpa.equalsIgnoreCase(payeeVpa)) {
            throw new IllegalArgumentException("payer and payee must be different");
        }
        String requestHash = hash(payerVpa, payeeVpa, amount);

        Optional<IdempotencyRecord> existing = idempotency.findById(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        Payment created;
        try {
            // Payment + idempotency record commit together, or not at all.
            created = tx.execute(status -> {
                Payment p = payments.save(new Payment(payerVpa, payeeVpa, amount));
                idempotency.saveAndFlush(new IdempotencyRecord(idempotencyKey, p.getId(), requestHash));
                return p;
            });
        } catch (DataIntegrityViolationException race) {
            // A concurrent request with the same key committed first; ours rolled back.
            IdempotencyRecord winner = idempotency.findById(idempotencyKey).orElseThrow(() -> race);
            return replay(winner, requestHash);
        }
        return advanceSafely(created);
    }

    public Optional<Payment> get(String txnId) {
        return payments.findById(txnId);
    }

    /**
     * Moves a payment forward from whatever state it is in until it is finished or
     * blocked on an unknown bank outcome. Used by new requests AND by the recovery
     * sweeper, so there is one implementation of the saga, not two.
     *
     * Deliberately not @Transactional: each state change commits on its own, and no
     * database transaction is held open while waiting on a bank.
     */
    public Payment advance(Payment payment) {
        Payment p = payment;
        while (!p.getState().isTerminal()) {
            switch (p.getState()) {
                case RECEIVED -> p = move(p, DEBIT_PENDING);

                case DEBIT_PENDING -> {
                    BankResponse r;
                    try {
                        r = bank.debit(p.getId(), p.getPayerVpa(), p.getAmount());
                    } catch (BankTimeoutException e) {
                        return awaitRecovery(p, "debit");
                    }
                    p = r.approved() ? move(p, DEBITED) : fail(p, FAILED, "debit declined: " + r.code());
                }

                case DEBITED -> p = move(p, CREDIT_PENDING);

                case CREDIT_PENDING -> {
                    BankResponse r;
                    try {
                        r = bank.credit(p.getId(), p.getPayeeVpa(), p.getAmount());
                    } catch (BankTimeoutException e) {
                        // The credit may have landed. Reversing now could pay out twice,
                        // so wait and re-send the same credit instead.
                        return awaitRecovery(p, "credit");
                    }
                    p = r.approved() ? move(p, SUCCESS) : fail(p, CREDIT_FAILED, "credit declined: " + r.code());
                }

                case CREDIT_FAILED -> p = move(p, REVERSAL_PENDING);

                case REVERSAL_PENDING -> {
                    BankResponse r;
                    try {
                        r = bank.reverse(p.getId(), p.getPayerVpa(), p.getAmount());
                    } catch (BankTimeoutException e) {
                        return awaitRecovery(p, "reversal");
                    }
                    if (!r.approved()) {
                        log.error("Txn {}: payer bank refused the reversal ({}); needs manual review",
                                p.getId(), r.code());
                        return p;
                    }
                    p = move(p, REVERSED);
                }

                default -> throw new IllegalStateException("Unhandled state " + p.getState());
            }
        }
        return p;
    }

    /** If the sweeper moved this payment first, return its latest state instead of erroring. */
    private Payment advanceSafely(Payment p) {
        try {
            return advance(p);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("Txn {} was advanced concurrently; returning latest state", p.getId());
            return payments.findById(p.getId()).orElseThrow();
        }
    }

    private Payment replay(IdempotencyRecord rec, String requestHash) {
        if (!rec.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("Idempotency-Key '" + rec.getIdempotencyKey()
                    + "' was already used for a different payment");
        }
        log.info("Idempotent replay of key {} -> txn {}", rec.getIdempotencyKey(), rec.getPaymentId());
        return payments.findById(rec.getPaymentId()).orElseThrow();
    }

    /** Commit the transition now and continue with the returned copy (it carries the new @Version). */
    private Payment move(Payment p, PaymentState next) {
        p.transitionTo(next);
        return payments.saveAndFlush(p);
    }

    private Payment fail(Payment p, PaymentState next, String reason) {
        p.markFailed(next, reason);
        return payments.saveAndFlush(p);
    }

    private Payment awaitRecovery(Payment p, String leg) {
        log.warn("Txn {}: {} outcome unknown; staying {} for the recovery sweeper", p.getId(), leg, p.getState());
        return p;
    }

    private static String hash(String payerVpa, String payeeVpa, BigDecimal amount) {
        String canonical = payerVpa.toLowerCase() + "|" + payeeVpa.toLowerCase() + "|"
                + amount.stripTrailingZeros().toPlainString();
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static class IdempotencyConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IdempotencyConflictException(String message) { super(message); }
    }
}
