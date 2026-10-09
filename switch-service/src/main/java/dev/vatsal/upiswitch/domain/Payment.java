package dev.vatsal.upiswitch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment",
       // Exactly the columns the recovery sweeper filters on.
       indexes = @Index(name = "idx_payment_state_updated", columnList = "state, updated_at"))
public class Payment {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "payer_vpa", length = 64, nullable = false, updatable = false)
    private String payerVpa;

    @Column(name = "payee_vpa", length = 64, nullable = false, updatable = false)
    private String payeeVpa;

    @Column(name = "amount", precision = 18, scale = 2, nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 20, nullable = false)
    private PaymentState state;

    @Column(name = "failure_reason", length = 256)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic lock. If a live request and the recovery sweeper try to move the same
     * payment at once, the second write fails instead of silently overwriting the first.
     * A null version also tells Spring Data this is a new entity (persist, not merge).
     */
    @Version
    @Column(name = "version")
    private Long version;

    protected Payment() { /* for JPA */ }

    public Payment(String payerVpa, String payeeVpa, BigDecimal amount) {
        this.id = UUID.randomUUID().toString();
        this.payerVpa = payerVpa;
        this.payeeVpa = payeeVpa;
        this.amount = amount;
        this.state = PaymentState.RECEIVED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Enforces the state machine: an illegal move throws instead of corrupting the payment. */
    public void transitionTo(PaymentState next) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal transition " + state + " -> " + next + " for txn " + id);
        }
        this.state = next;
        this.updatedAt = Instant.now();
    }

    public void markFailed(PaymentState next, String reason) {
        transitionTo(next);
        this.failureReason = reason;
    }

    public String getId() { return id; }
    public String getPayerVpa() { return payerVpa; }
    public String getPayeeVpa() { return payeeVpa; }
    public BigDecimal getAmount() { return amount; }
    public PaymentState getState() { return state; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
