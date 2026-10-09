package dev.vatsal.banksim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(name = "vpa", length = 64, nullable = false, updatable = false)
    private String vpa;

    @Column(name = "balance", precision = 18, scale = 2, nullable = false)
    private BigDecimal balance;

    @Version
    @Column(name = "version")
    private Long version;

    protected Account() { /* for JPA */ }

    public Account(String vpa, BigDecimal balance) {
        this.vpa = vpa;
        this.balance = balance;
    }

    public void debit(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient funds in " + vpa);
        }
        balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public String getVpa() { return vpa; }
    public BigDecimal getBalance() { return balance; }
}
