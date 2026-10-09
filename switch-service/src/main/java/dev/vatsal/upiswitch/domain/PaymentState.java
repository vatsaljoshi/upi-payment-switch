package dev.vatsal.upiswitch.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of a payment inside the switch.
 *
 * Money moves in two legs: debit the payer, then credit the payee. If the payee bank
 * definitively declines the credit, the debit is reversed. Terminal states are
 * SUCCESS, FAILED and REVERSED; every other state is "in flight" and is picked up by
 * the recovery sweeper if it stays there too long.
 */
public enum PaymentState {
    RECEIVED,          // accepted and persisted, nothing sent to a bank yet
    DEBIT_PENDING,     // debit requested from payer bank, outcome not yet known
    DEBITED,           // payer bank confirmed the debit
    CREDIT_PENDING,    // credit requested from payee bank, outcome not yet known
    SUCCESS,           // payee credited: done
    CREDIT_FAILED,     // payee bank definitively declined: debit must be reversed
    REVERSAL_PENDING,  // reversal requested from payer bank, outcome not yet known
    REVERSED,          // payer refunded: done
    FAILED;            // debit declined: no money moved, done

    private static final Map<PaymentState, Set<PaymentState>> ALLOWED = Map.of(
        RECEIVED,         EnumSet.of(DEBIT_PENDING),
        DEBIT_PENDING,    EnumSet.of(DEBITED, FAILED),
        DEBITED,          EnumSet.of(CREDIT_PENDING),
        CREDIT_PENDING,   EnumSet.of(SUCCESS, CREDIT_FAILED),
        CREDIT_FAILED,    EnumSet.of(REVERSAL_PENDING),
        REVERSAL_PENDING, EnumSet.of(REVERSED),
        SUCCESS,          EnumSet.noneOf(PaymentState.class),
        REVERSED,         EnumSet.noneOf(PaymentState.class),
        FAILED,           EnumSet.noneOf(PaymentState.class)
    );

    public boolean canTransitionTo(PaymentState next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == REVERSED;
    }
}
