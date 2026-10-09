package dev.vatsal.banksim.service;

import dev.vatsal.banksim.domain.Account;
import dev.vatsal.banksim.domain.LedgerEntry;
import dev.vatsal.banksim.repo.AccountRepository;
import dev.vatsal.banksim.repo.LedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * A stand-in member bank. It keeps balances, enforces sufficient funds, locks the
 * account row while applying a leg, and answers a re-sent leg with its original result.
 */
@Service
public class BankService {

    private static final Logger log = LoggerFactory.getLogger(BankService.class);

    static final String DEBIT = "DEBIT";
    static final String CREDIT = "CREDIT";
    static final String REVERSE = "REVERSE";

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public BankService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    public record Result(String status, String code, String message) {
        static Result approved() { return new Result("APPROVED", "00", "approved"); }
        static Result declined(String code, String message) { return new Result("DECLINED", code, message); }
    }

    @Transactional
    public Result debit(String txnId, String vpa, BigDecimal amount) {
        Optional<Account> account = accounts.findForUpdate(vpa);
        Optional<LedgerEntry> prior = ledger.findByTxnIdAndLeg(txnId, DEBIT);
        if (prior.isPresent()) {
            return replay(prior.get());
        }
        if (account.isEmpty()) {
            return record(txnId, DEBIT, vpa, amount, Result.declined("AC01", "unknown payer account"));
        }
        if (account.get().getBalance().compareTo(amount) < 0) {
            return record(txnId, DEBIT, vpa, amount, Result.declined("AM04", "insufficient funds"));
        }
        account.get().debit(amount);
        return record(txnId, DEBIT, vpa, amount, Result.approved());
    }

    @Transactional
    public Result credit(String txnId, String vpa, BigDecimal amount) {
        Optional<Account> account = accounts.findForUpdate(vpa);
        Optional<LedgerEntry> prior = ledger.findByTxnIdAndLeg(txnId, CREDIT);
        if (prior.isPresent()) {
            return replay(prior.get());
        }
        if (account.isEmpty()) {
            return record(txnId, CREDIT, vpa, amount, Result.declined("AC01", "unknown payee account"));
        }
        account.get().credit(amount);
        return record(txnId, CREDIT, vpa, amount, Result.approved());
    }

    /** Refunds a debit. Refuses if there is no matching approved debit to undo. */
    @Transactional
    public Result reverse(String txnId, String vpa, BigDecimal amount) {
        Optional<Account> account = accounts.findForUpdate(vpa);
        Optional<LedgerEntry> prior = ledger.findByTxnIdAndLeg(txnId, REVERSE);
        if (prior.isPresent()) {
            return replay(prior.get());
        }
        boolean matchingDebit = ledger.findByTxnIdAndLeg(txnId, DEBIT)
                .filter(d -> "APPROVED".equals(d.getResult()))
                .filter(d -> d.getVpa().equals(vpa) && d.getAmount().compareTo(amount) == 0)
                .isPresent();
        if (!matchingDebit || account.isEmpty()) {
            return record(txnId, REVERSE, vpa, amount, Result.declined("RV01", "no matching debit to reverse"));
        }
        account.get().credit(amount);
        log.info("Reversed {} to {} for txn {}", amount, vpa, txnId);
        return record(txnId, REVERSE, vpa, amount, Result.approved());
    }

    /** Used by fault injection: the payee bank definitively declines this credit. */
    @Transactional
    public Result declineCredit(String txnId, String vpa, BigDecimal amount) {
        Optional<LedgerEntry> prior = ledger.findByTxnIdAndLeg(txnId, CREDIT);
        if (prior.isPresent()) {
            return replay(prior.get());
        }
        return record(txnId, CREDIT, vpa, amount, Result.declined("U30", "credit declined by payee bank"));
    }

    @Transactional(readOnly = true)
    public Optional<Account> account(String vpa) {
        return accounts.findById(vpa);
    }

    private Result replay(LedgerEntry e) {
        return new Result(e.getResult(), e.getCode(), "duplicate request: original result returned");
    }

    private Result record(String txnId, String leg, String vpa, BigDecimal amount, Result result) {
        ledger.save(new LedgerEntry(txnId, leg, vpa, amount, result.status(), result.code()));
        return result;
    }
}
