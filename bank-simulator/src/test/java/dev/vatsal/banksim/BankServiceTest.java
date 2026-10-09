package dev.vatsal.banksim;

import dev.vatsal.banksim.repo.AccountRepository;
import dev.vatsal.banksim.service.BankService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class BankServiceTest {

    private static final String ALICE = "alice@switchbank";

    @Autowired BankService bank;
    @Autowired AccountRepository accounts;

    private BigDecimal balance(String vpa) {
        return accounts.findById(vpa).orElseThrow().getBalance();
    }

    private static String txn() {
        return UUID.randomUUID().toString();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void resentDebitIsAppliedOnlyOnce() {
        String t = txn();
        BigDecimal before = balance(ALICE);

        assertEquals("APPROVED", bank.debit(t, ALICE, new BigDecimal("100.00")).status());
        assertEquals("APPROVED", bank.debit(t, ALICE, new BigDecimal("100.00")).status());

        assertMoney(before.subtract(new BigDecimal("100.00")).toPlainString(), balance(ALICE));
    }

    @Test
    void insufficientFundsIsDeclinedAndReplayedWithSameCode() {
        String t = txn();
        BankService.Result first = bank.debit(t, "carol@otherbank", new BigDecimal("10.00"));
        BankService.Result again = bank.debit(t, "carol@otherbank", new BigDecimal("10.00"));

        assertEquals("DECLINED", first.status());
        assertEquals("AM04", first.code());
        assertEquals("DECLINED", again.status());
        assertEquals("AM04", again.code());
    }

    @Test
    void reversalRefundsExactlyOnce() {
        String t = txn();
        BigDecimal before = balance(ALICE);

        bank.debit(t, ALICE, new BigDecimal("40.00"));
        assertEquals("APPROVED", bank.reverse(t, ALICE, new BigDecimal("40.00")).status());
        assertEquals("APPROVED", bank.reverse(t, ALICE, new BigDecimal("40.00")).status());

        assertMoney(before.toPlainString(), balance(ALICE));
    }

    @Test
    void reversalWithoutAMatchingDebitIsRefused() {
        BigDecimal before = balance(ALICE);

        BankService.Result r = bank.reverse(txn(), ALICE, new BigDecimal("40.00"));

        assertEquals("DECLINED", r.status());
        assertEquals("RV01", r.code());
        assertMoney(before.toPlainString(), balance(ALICE));
    }

    @Test
    void creditToUnknownAccountIsDeclined() {
        BankService.Result r = bank.credit(txn(), "ghost@otherbank", new BigDecimal("5.00"));

        assertEquals("DECLINED", r.status());
        assertEquals("AC01", r.code());
    }
}
