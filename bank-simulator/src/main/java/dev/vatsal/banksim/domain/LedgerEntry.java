package dev.vatsal.banksim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One processed leg (DEBIT, CREDIT or REVERSE) of a switch transaction, approved or not.
 * The unique (txn_id, leg) constraint is what makes the bank idempotent: a re-sent leg
 * finds its original entry and gets the original answer instead of being applied twice.
 */
@Entity
@Table(name = "ledger_entry",
       uniqueConstraints = @UniqueConstraint(name = "uk_ledger_txn_leg", columnNames = {"txn_id", "leg"}))
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_id", length = 36, nullable = false)
    private String txnId;

    @Column(name = "leg", length = 10, nullable = false)
    private String leg;

    @Column(name = "vpa", length = 64, nullable = false)
    private String vpa;

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "result", length = 10, nullable = false)
    private String result;

    @Column(name = "code", length = 8, nullable = false)
    private String code;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() { /* for JPA */ }

    public LedgerEntry(String txnId, String leg, String vpa, BigDecimal amount, String result, String code) {
        this.txnId = txnId;
        this.leg = leg;
        this.vpa = vpa;
        this.amount = amount;
        this.result = result;
        this.code = code;
        this.createdAt = Instant.now();
    }

    public String getTxnId() { return txnId; }
    public String getLeg() { return leg; }
    public String getVpa() { return vpa; }
    public BigDecimal getAmount() { return amount; }
    public String getResult() { return result; }
    public String getCode() { return code; }
}
