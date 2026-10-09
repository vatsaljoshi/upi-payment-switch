package dev.vatsal.upiswitch.service;

import java.math.BigDecimal;

/**
 * Outbound port to a member bank: the switch asks a bank to debit, credit or reverse.
 *
 * The contract that matters: a DECLINED response is a definitive "no", but a
 * {@link BankTimeoutException} means "we don't know" — the bank may or may not have
 * applied the leg. The switch handles those two cases completely differently.
 * Banks must be idempotent per (txnId, leg) so an unknown leg can be safely re-sent.
 */
public interface BankClient {

    BankResponse debit(String txnId, String vpa, BigDecimal amount);

    BankResponse credit(String txnId, String vpa, BigDecimal amount);

    BankResponse reverse(String txnId, String vpa, BigDecimal amount);

    enum Status { APPROVED, DECLINED }

    record BankResponse(Status status, String code, String message) {
        public boolean approved() { return status == Status.APPROVED; }
    }

    /** The bank's answer is unknown (timeout, connection failure, 5xx). */
    class BankTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BankTimeoutException(String message) { super(message); }
        public BankTimeoutException(String message, Throwable cause) { super(message, cause); }
    }
}
