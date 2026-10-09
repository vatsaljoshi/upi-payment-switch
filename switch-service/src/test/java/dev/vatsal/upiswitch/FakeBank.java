package dev.vatsal.upiswitch;

import dev.vatsal.upiswitch.service.BankClient;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory bank for tests. Idempotent per (txnId, leg), with knobs to force failures. */
public class FakeBank implements BankClient {

    public final AtomicInteger debits = new AtomicInteger();
    public final AtomicInteger credits = new AtomicInteger();
    public final AtomicInteger reversals = new AtomicInteger();

    public final AtomicInteger creditTimeouts = new AtomicInteger();
    public final AtomicInteger reverseTimeouts = new AtomicInteger();
    public volatile boolean declineNextDebit;
    public volatile boolean declineNextCredit;

    private final Set<String> applied = ConcurrentHashMap.newKeySet();

    public void reset() {
        debits.set(0);
        credits.set(0);
        reversals.set(0);
        creditTimeouts.set(0);
        reverseTimeouts.set(0);
        declineNextDebit = false;
        declineNextCredit = false;
        applied.clear();
    }

    @Override
    public BankResponse debit(String txnId, String vpa, BigDecimal amount) {
        if (declineNextDebit) {
            declineNextDebit = false;
            return new BankResponse(Status.DECLINED, "AM04", "insufficient funds");
        }
        if (applied.add(txnId + ":DEBIT")) {
            debits.incrementAndGet();
        }
        return new BankResponse(Status.APPROVED, "00", "approved");
    }

    @Override
    public BankResponse credit(String txnId, String vpa, BigDecimal amount) {
        if (takeOne(creditTimeouts)) {
            throw new BankTimeoutException("simulated credit timeout");
        }
        if (declineNextCredit) {
            declineNextCredit = false;
            return new BankResponse(Status.DECLINED, "U30", "credit declined");
        }
        if (applied.add(txnId + ":CREDIT")) {
            credits.incrementAndGet();
        }
        return new BankResponse(Status.APPROVED, "00", "approved");
    }

    @Override
    public BankResponse reverse(String txnId, String vpa, BigDecimal amount) {
        if (takeOne(reverseTimeouts)) {
            throw new BankTimeoutException("simulated reversal timeout");
        }
        if (applied.add(txnId + ":REVERSE")) {
            reversals.incrementAndGet();
        }
        return new BankResponse(Status.APPROVED, "00", "approved");
    }

    private static boolean takeOne(AtomicInteger counter) {
        return counter.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }
}
