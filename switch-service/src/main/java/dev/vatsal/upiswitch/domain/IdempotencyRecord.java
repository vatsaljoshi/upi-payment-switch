package dev.vatsal.upiswitch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Maps a client's Idempotency-Key to the payment it created.
 *
 * The key is the primary key, so the database guarantees one payment per key even when
 * two identical requests race. Implementing Persistable makes save() do an INSERT
 * (which fails on a duplicate key) rather than a merge (which could silently match the
 * other request's row and let both requests through).
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key", length = 80, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "payment_id", length = 36, nullable = false, updatable = false)
    private String paymentId;

    /** SHA-256 of the request details, to reject a key reused for a different payment. */
    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected IdempotencyRecord() { /* for JPA */ }

    public IdempotencyRecord(String idempotencyKey, String paymentId, String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.paymentId = paymentId;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
    }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }

    @Override public String getId() { return idempotencyKey; }
    @Override public boolean isNew() { return isNew; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getPaymentId() { return paymentId; }
    public String getRequestHash() { return requestHash; }
    public Instant getCreatedAt() { return createdAt; }
}
