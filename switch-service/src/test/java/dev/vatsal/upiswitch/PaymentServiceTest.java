package dev.vatsal.upiswitch;

import dev.vatsal.upiswitch.domain.Payment;
import dev.vatsal.upiswitch.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.UUID;

import static dev.vatsal.upiswitch.domain.PaymentState.CREDIT_PENDING;
import static dev.vatsal.upiswitch.domain.PaymentState.FAILED;
import static dev.vatsal.upiswitch.domain.PaymentState.REVERSAL_PENDING;
import static dev.vatsal.upiswitch.domain.PaymentState.REVERSED;
import static dev.vatsal.upiswitch.domain.PaymentState.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "switch.sweeper.enabled=false")
@Import(TestBankConfig.class)
class PaymentServiceTest {

    private static final String ALICE = "alice@switchbank";
    private static final String BOB = "bob@otherbank";

    @Autowired PaymentService service;
    @Autowired FakeBank bank;

    @BeforeEach
    void resetBank() {
        bank.reset();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static BigDecimal inr(String value) {
        return new BigDecimal(value);
    }

    @Test
    void happyPathDebitsOnceCreditsOnceAndSettles() {
        Payment p = service.submit(newKey(), ALICE, BOB, inr("100.00"));

        assertEquals(SUCCESS, p.getState());
        assertEquals(1, bank.debits.get());
        assertEquals(1, bank.credits.get());
        assertEquals(0, bank.reversals.get());
    }

    @Test
    void retryWithSameKeyReturnsSameTxnAndDoesNotChargeAgain() {
        String key = newKey();
        Payment first = service.submit(key, ALICE, BOB, inr("50.00"));
        Payment retry = service.submit(key, ALICE, BOB, inr("50.00"));

        assertEquals(first.getId(), retry.getId());
        assertEquals(1, bank.debits.get());
        assertEquals(1, bank.credits.get());
    }

    /** 50.00 and 50.0 are the same payment; the hash must not care about trailing zeros. */
    @Test
    void sameKeyWithEquivalentAmountIsStillAReplay() {
        String key = newKey();
        Payment first = service.submit(key, ALICE, BOB, inr("50.00"));
        Payment retry = service.submit(key, ALICE, BOB, inr("50.0"));

        assertEquals(first.getId(), retry.getId());
        assertEquals(1, bank.debits.get());
    }

    @Test
    void sameKeyWithDifferentAmountIsRejected() {
        String key = newKey();
        service.submit(key, ALICE, BOB, inr("10.00"));

        assertThrows(PaymentService.IdempotencyConflictException.class,
                () -> service.submit(key, ALICE, BOB, inr("999.00")));
    }

    @Test
    void debitDeclineFailsWithoutEverTouchingThePayee() {
        bank.declineNextDebit = true;

        Payment p = service.submit(newKey(), ALICE, BOB, inr("10.00"));

        assertEquals(FAILED, p.getState());
        assertTrue(p.getFailureReason().contains("AM04"));
        assertEquals(0, bank.credits.get());
        assertEquals(0, bank.reversals.get());
    }

    @Test
    void creditDeclineReversesTheDebit() {
        bank.declineNextCredit = true;

        Payment p = service.submit(newKey(), ALICE, BOB, inr("75.00"));

        assertEquals(REVERSED, p.getState());
        assertEquals(1, bank.debits.get());
        assertEquals(1, bank.reversals.get());
    }

    /** The most important rule: an unknown credit outcome must never trigger a refund. */
    @Test
    void creditTimeoutIsNotReversedAndIsCompletedByRecovery() {
        bank.creditTimeouts.set(1);

        Payment p = service.submit(newKey(), ALICE, BOB, inr("25.00"));
        assertEquals(CREDIT_PENDING, p.getState());
        assertEquals(0, bank.reversals.get());

        Payment recovered = service.advance(service.get(p.getId()).orElseThrow());
        assertEquals(SUCCESS, recovered.getState());
        assertEquals(1, bank.credits.get());
        assertEquals(0, bank.reversals.get());
    }

    @Test
    void reversalTimeoutStaysPendingUntilConfirmed() {
        bank.declineNextCredit = true;
        bank.reverseTimeouts.set(1);

        Payment p = service.submit(newKey(), ALICE, BOB, inr("30.00"));
        assertEquals(REVERSAL_PENDING, p.getState());

        Payment recovered = service.advance(service.get(p.getId()).orElseThrow());
        assertEquals(REVERSED, recovered.getState());
        assertEquals(1, bank.reversals.get());
    }

    @Test
    void payingYourselfIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(newKey(), ALICE, ALICE, inr("5.00")));
    }
}
