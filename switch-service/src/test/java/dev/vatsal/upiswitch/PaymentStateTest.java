package dev.vatsal.upiswitch;

import org.junit.jupiter.api.Test;

import static dev.vatsal.upiswitch.domain.PaymentState.CREDIT_FAILED;
import static dev.vatsal.upiswitch.domain.PaymentState.CREDIT_PENDING;
import static dev.vatsal.upiswitch.domain.PaymentState.DEBITED;
import static dev.vatsal.upiswitch.domain.PaymentState.DEBIT_PENDING;
import static dev.vatsal.upiswitch.domain.PaymentState.FAILED;
import static dev.vatsal.upiswitch.domain.PaymentState.RECEIVED;
import static dev.vatsal.upiswitch.domain.PaymentState.REVERSAL_PENDING;
import static dev.vatsal.upiswitch.domain.PaymentState.REVERSED;
import static dev.vatsal.upiswitch.domain.PaymentState.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentStateTest {

    @Test
    void happyPathTransitionsAreAllowed() {
        assertTrue(RECEIVED.canTransitionTo(DEBIT_PENDING));
        assertTrue(DEBIT_PENDING.canTransitionTo(DEBITED));
        assertTrue(DEBITED.canTransitionTo(CREDIT_PENDING));
        assertTrue(CREDIT_PENDING.canTransitionTo(SUCCESS));
    }

    @Test
    void reversalPathIsAllowed() {
        assertTrue(CREDIT_PENDING.canTransitionTo(CREDIT_FAILED));
        assertTrue(CREDIT_FAILED.canTransitionTo(REVERSAL_PENDING));
        assertTrue(REVERSAL_PENDING.canTransitionTo(REVERSED));
    }

    @Test
    void moneyMovingShortcutsAreRejected() {
        assertFalse(RECEIVED.canTransitionTo(SUCCESS));   // cannot settle without moving money
        assertFalse(DEBITED.canTransitionTo(REVERSED));   // reversal must be confirmed first
        assertFalse(DEBIT_PENDING.canTransitionTo(SUCCESS));
    }

    @Test
    void terminalStatesCannotChange() {
        assertTrue(SUCCESS.isTerminal());
        assertTrue(FAILED.isTerminal());
        assertTrue(REVERSED.isTerminal());
        assertFalse(SUCCESS.canTransitionTo(FAILED));
        assertFalse(REVERSED.canTransitionTo(SUCCESS));
        assertFalse(CREDIT_PENDING.isTerminal());
    }
}
